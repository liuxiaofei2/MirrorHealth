package com.magicmirror.collect.net

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.magicmirror.collect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 服务端通信客户端。
 *
 * 约定：
 *  - 所有写请求带 X-Request-Id，服务端 5 分钟内去重，保证网络重试安全
 *  - 鉴权走 X-Device-Token
 */
object ApiClient {

    private const val BASE = BuildConfig.API_BASE_URL
    private const val TOKEN = BuildConfig.DEVICE_TOKEN
    private const val DEVICE_ID = BuildConfig.DEVICE_ID

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val JPEG = "image/jpeg".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson: Gson = GsonBuilder().create()

    fun deviceId(): String = DEVICE_ID

    private fun base(path: String) = Request.Builder()
        .url("$BASE$path")
        .header("X-Device-Token", TOKEN)
        .header("X-Request-Id", UUID.randomUUID().toString())

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            }
            body
        }
    }

    private suspend fun postJson(path: String, payload: Any?): String {
        val json = gson.toJson(payload)
        val req = base(path).post(json.toRequestBody(JSON)).build()
        return execute(req)
    }

    // ---------------- 设备 ----------------

    suspend fun activateDevice(bindCode: String? = BuildConfig.BIND_CODE): DeviceActivateResp {
        val body = postJson("/api/v1/device/activate", DeviceActivateReq(
            deviceId = DEVICE_ID, type = "mirror",
            model = android.os.Build.MODEL, firmware = android.os.Build.VERSION.RELEASE,
            bindCode = bindCode,
        ))
        return gson.fromJson(body, DeviceActivateResp::class.java)
    }

    // ---------------- 会话 ----------------

    suspend fun openSession(trigger: String = "mat"): SessionResp {
        val body = postJson("/api/v1/sessions/open", SessionOpenReq(DEVICE_ID, trigger))
        return gson.fromJson(body, SessionResp::class.java)
    }

    suspend fun sessionStatus(sessionId: String): SessionResp {
        val req = base("/api/v1/sessions/$sessionId").get().build()
        return gson.fromJson(execute(req), SessionResp::class.java)
    }

    // ---------------- 人脸识别 ----------------

    suspend fun recognize(sessionId: String, jpeg: ByteArray): RecognizeResp {
        val part = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "face.jpg", jpeg.toRequestBody(JPEG))
            .build()
        val req = base("/api/v1/sessions/$sessionId/recognize").post(part).build()
        return gson.fromJson(execute(req), RecognizeResp::class.java)
    }

    // ---------------- 影像 ----------------

    suspend fun uploadMedia(sessionId: String, kind: String, jpeg: ByteArray,
                            quality: Double? = null): MediaResp {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("kind", kind)
            .addFormDataPart("file", "$kind.jpg", jpeg.toRequestBody(JPEG))
        if (quality != null) {
            builder.addFormDataPart("quality_score", quality.toString())
        }
        val req = base("/api/v1/sessions/$sessionId/media").post(builder.build()).build()
        return gson.fromJson(execute(req), MediaResp::class.java)
    }

    // ---------------- 指标 ----------------

    suspend fun uploadMetrics(sessionId: String, items: List<MetricItem>) {
        if (items.isEmpty()) return
        postJson("/api/v1/sessions/$sessionId/metrics", MetricsReq(items))
    }

    // ---------------- 提交 ----------------

    suspend fun commit(sessionId: String, brushSeconds: Int): CommitResp {
        val body = postJson("/api/v1/sessions/$sessionId/commit",
            CommitReq(brushSeconds = brushSeconds, forceAnalyze = true))
        return gson.fromJson(body, CommitResp::class.java)
    }
}
