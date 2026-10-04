package com.magicmirror.collect.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicmirror.collect.ble.MatScaleManager
import com.magicmirror.collect.data.LocalQueue
import com.magicmirror.collect.net.ApiClient
import com.magicmirror.collect.net.AssessmentDto
import com.magicmirror.collect.net.MetricItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 采集流程状态机。
 *
 * IDLE → SCANNING → WAITING_MAT → RECOGNIZING → BRUSHING → UPLOADING → DONE
 * 任一步失败进入 FAILED，但已采集的数据会被缓存，不会丢失。
 */
class MirrorViewModel(app: Application) : AndroidViewModel(app) {

    enum class Stage { IDLE, SCANNING, WAITING_MAT, RECOGNIZING, BRUSHING, UPLOADING, DONE, FAILED }

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val matName: String? = null,
        val liveWeight: Double? = null,
        val weight: Double? = null,
        val memberName: String? = null,
        val confidence: Double = 0.0,
        val brushLeft: Int = 0,
        val message: String = "准备就绪",
        val summary: String? = null,
        val level: String? = null,
        val assessments: List<AssessmentDto> = emptyList(),
        val uploadProgress: String = "",
    )

    companion object {
        private const val TAG = "MirrorVM"
        const val BRUSH_SECONDS = 120
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val matScale = MatScaleManager(app)
    private val queue = LocalQueue(app)

    /** 由 UI 层注入：按类型抓拍一帧并返回 JPEG 字节。 */
    var frameProvider: (suspend (String) -> ByteArray?)? = null

    /** 由 UI 层注入：语音播报。 */
    var speaker: ((String) -> Unit)? = null

    private var sessionId: String? = null
    private var brushJob: Job? = null

    init {
        queue.prune()
    }

    // ------------------------------------------------------------ 启动

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(stage = Stage.SCANNING, message = "正在连接魔镜与地垫…") }
            runCatching { ApiClient.activateDevice() }
                .onSuccess { resp ->
                    Log.i(TAG, "设备激活成功 family=${resp.familyId}")
                    if (!resp.bound) {
                        _state.update { it.copy(message = "设备尚未绑定家庭，请在小程序中绑定「${ApiClient.deviceId()}」") }
                    }
                }
                .onFailure { Log.w(TAG, "设备激活失败，离线模式继续: ${it.message}") }

            matScale.startScan()
            _state.update { it.copy(stage = Stage.WAITING_MAT, message = "请站上地垫") }
        }
    }

    fun onMatEvent(event: MatScaleManager.Event) {
        when (event) {
            is MatScaleManager.Event.Found ->
                _state.update { it.copy(matName = event.name, message = "已发现地垫，正在连接…") }
            is MatScaleManager.Event.Connected -> {
                speak("地垫已连接")
                _state.update { it.copy(matName = event.name, message = "地垫已连接，请站上称重") }
            }
            is MatScaleManager.Event.LiveWeight ->
                _state.update { it.copy(liveWeight = event.kg) }
            is MatScaleManager.Event.StableWeight -> onStableWeight(event.kg)
            is MatScaleManager.Event.Error -> fail(event.message)
            MatScaleManager.Event.Disconnected ->
                _state.update { it.copy(matName = null, message = "地垫已断开") }
        }
    }

    // ------------------------------------------------------------ 会话

    private fun onStableWeight(kg: Double) {
        if (_state.value.stage != Stage.WAITING_MAT && _state.value.stage != Stage.SCANNING) return
        viewModelScope.launch {
            _state.update { it.copy(weight = kg, message = "体重 ${"%.1f".format(kg)} kg，正在识别…") }
            runCatching { ApiClient.openSession("mat") }
                .onSuccess { resp ->
                    sessionId = resp.sessionId
                    _state.update { it.copy(stage = Stage.RECOGNIZING, message = "请看镜面，正在识别身份") }
                }
                .onFailure { fail("开启采集会话失败：${it.message}") }
        }
    }

    fun onFaceRecognized(jpeg: ByteArray) {
        val sid = sessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(stage = Stage.RECOGNIZING, message = "正在识别身份…") }
            runCatching { ApiClient.recognize(sid, jpeg) }
                .onSuccess { resp ->
                    if (resp.matched) {
                        speak("${resp.memberNickname}，早上好，请开始刷牙")
                        _state.update {
                            it.copy(stage = Stage.BRUSHING, memberName = resp.memberNickname,
                                confidence = resp.confidence,
                                message = "已识别为 ${resp.memberNickname}，开始刷牙")
                        }
                        runBrushCycle(sid)
                    } else {
                        _state.update {
                            it.copy(message = "未匹配到成员，可在小程序登记人脸后重试")
                        }
                    }
                }
                .onFailure { fail("人脸识别失败：${it.message}") }
        }
    }

    private fun runBrushCycle(sid: String) {
        brushJob?.cancel()
        brushJob = viewModelScope.launch {
            var left = BRUSH_SECONDS
            while (left > 0) {
                _state.update { it.copy(brushLeft = left) }
                when (left) {
                    BRUSH_SECONDS - 3 -> capture("face")
                    45 -> capture("face")
                    8 -> capture("teeth")
                }
                delay(1000)
                left--
            }
            submit(sid)
        }
    }

    private suspend fun capture(kind: String) {
        val jpeg = frameProvider?.invoke(kind) ?: return
        val sid = sessionId ?: return
        runCatching {
            ApiClient.uploadMedia(sid, kind, jpeg, quality = 0.7)
            Log.i(TAG, "已上传 $kind 影像 ${jpeg.size} bytes")
        }.onFailure { Log.w(TAG, "上传 $kind 失败: ${it.message}") }
    }

    // ------------------------------------------------------------ 提交

    private suspend fun submit(sid: String) {
        _state.update { it.copy(stage = Stage.UPLOADING, message = "正在上传并分析…") }
        val weight = _state.value.weight

        val items = buildList {
            if (weight != null) {
                add(MetricItem("weight", weight, "kg", "mat"))
                add(MetricItem("brush_seconds", BRUSH_SECONDS.toDouble(), "s", "mirror"))
            }
        }

        val result = runCatching {
            ApiClient.uploadMetrics(sid, items)
            ApiClient.commit(sid, BRUSH_SECONDS)
        }

        result.onSuccess { resp ->
            _state.update {
                it.copy(stage = Stage.DONE, level = resp.level, summary = resp.summary,
                    assessments = resp.assessments, message = "分析完成")
            }
            speak("健康数据采集完成")
        }.onFailure { e ->
            // 入队等待重传，不让用户白刷一次
            queue.enqueue(
                LocalQueue.PendingSession(
                    sessionId = sid, brushSeconds = BRUSH_SECONDS,
                    metrics = items.map { LocalQueue.PendingMetric(it.metric, it.value, it.unit, it.source) },
                    media = emptyList(),
                )
            )
            fail("提交失败，已缓存本地，将在网络恢复后自动补传：${e.message}")
        }
    }

    /** 补传历史缓存。 */
    fun flushPending() {
        viewModelScope.launch {
            val pending = queue.all()
            if (pending.isEmpty()) return@launch
            Log.i(TAG, "发现 ${pending.size} 条待补传记录")
            for (item in pending) {
                runCatching {
                    ApiClient.uploadMetrics(item.sessionId, item.metrics.map {
                        MetricItem(it.metric, it.value, it.unit, it.source)
                    })
                    ApiClient.commit(item.sessionId, item.brushSeconds)
                    queue.remove(item.sessionId)
                }.onFailure { Log.w(TAG, "补传失败，保留缓存: ${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------ 工具

    fun reset() {
        brushJob?.cancel()
        sessionId = null
        _state.value = UiState(stage = Stage.WAITING_MAT, message = "请站上地垫",
            matName = _state.value.matName)
    }

    fun overrideMember(name: String) {
        _state.update { it.copy(memberName = name) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(stage = Stage.FAILED, message = message) }
        speak("采集出现问题，请在小程序中查看")
    }

    private fun speak(text: String) {
        speaker?.invoke(text)
    }

    override fun onCleared() {
        matScale.close()
        super.onCleared()
    }
}
