package com.magicmirror.collect.net

import com.google.gson.annotations.SerializedName

// ---------------- 设备 ----------------

data class DeviceActivateReq(
    @SerializedName("device_id") val deviceId: String,
    val type: String = "mirror",
    val model: String? = null,
    val firmware: String? = null,
    @SerializedName("bind_code") val bindCode: String? = null,
)

data class DeviceActivateResp(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("family_id") val familyId: String?,
    val bound: Boolean,
)

// ---------------- 会话 ----------------

data class SessionOpenReq(
    @SerializedName("device_id") val deviceId: String,
    val trigger: String = "mat",
)

data class SessionResp(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("member_id") val memberId: String?,
    @SerializedName("member_nickname") val memberNickname: String?,
    val status: String,
    @SerializedName("started_at") val startedAt: String?,
)

data class RecognizeResp(
    val matched: Boolean,
    @SerializedName("member_id") val memberId: String?,
    @SerializedName("member_nickname") val memberNickname: String?,
    val confidence: Double,
    val margin: Double,
    val message: String,
)

// ---------------- 数据上报 ----------------

data class MetricItem(
    val metric: String,
    val value: Double,
    val unit: String = "",
    val source: String = "mirror",
)

data class MetricsReq(val items: List<MetricItem>)

data class MediaResp(
    @SerializedName("asset_id") val assetId: String,
    val kind: String,
    val url: String,
)

// ---------------- 提交分析 ----------------

data class CommitReq(
    @SerializedName("brush_seconds") val brushSeconds: Int?,
    @SerializedName("force_analyze") val forceAnalyze: Boolean = true,
)

data class AssessmentDto(
    val code: String,
    val category: String,
    val level: String,
    val title: String,
    val conclusion: String,
    val advice: String?,
)

data class CommitResp(
    @SerializedName("session_id") val sessionId: String,
    val status: String,
    @SerializedName("report_id") val reportId: String?,
    val level: String?,
    val summary: String?,
    val assessments: List<AssessmentDto> = emptyList(),
)
