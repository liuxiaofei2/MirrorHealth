package com.magicmirror.collect.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 断网缓存队列。
 *
 * 牙刷场景不能因为网络问题让用户重来一遍：采集到的影像与数值先落本地，
 * 提交失败时入队，下次开机或网络恢复后自动补传。
 */
class LocalQueue(context: Context) {

    data class PendingSession(
        val sessionId: String,
        val brushSeconds: Int,
        val metrics: List<PendingMetric>,
        val media: List<PendingMedia>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class PendingMetric(val metric: String, val value: Double, val unit: String, val source: String)

    data class PendingMedia(val kind: String, val path: String)

    private val gson = Gson()
    private val file = File(context.filesDir, "pending_sessions.json")

    private fun load(): MutableList<PendingSession> = try {
        if (!file.exists()) mutableListOf()
        else gson.fromJson<MutableList<PendingSession>>(
            file.readText(),
            object : TypeToken<MutableList<PendingSession>>() {}.type
        ) ?: mutableListOf()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun save(items: List<PendingSession>) {
        runCatching { file.writeText(gson.toJson(items)) }
    }

    @Synchronized
    fun enqueue(session: PendingSession) {
        val items = load()
        items.removeAll { it.sessionId == session.sessionId }
        items.add(session)
        save(items)
    }

    @Synchronized
    fun all(): List<PendingSession> = load()

    @Synchronized
    fun remove(sessionId: String) {
        val items = load()
        items.removeAll { it.sessionId == sessionId }
        save(items)
    }

    /** 清理超过 7 天的陈旧缓存，并删除其影像文件。 */
    @Synchronized
    fun prune(maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        val now = System.currentTimeMillis()
        val items = load()
        val keep = mutableListOf<PendingSession>()
        for (item in items) {
            if (now - item.createdAt > maxAgeMs) {
                item.media.forEach { runCatching { File(it.path).delete() } }
            } else {
                keep.add(item)
            }
        }
        if (keep.size != items.size) save(keep)
    }
}
