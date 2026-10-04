package com.magicmirror.collect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.magicmirror.collect.ble.MatScaleManager
import com.magicmirror.collect.ui.MirrorViewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果在 Compose 侧通过状态感知，这里无需额外处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
            }
        }

        requestRuntimePermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: MirrorViewModel = viewModel()
                MirrorScreen(vm, faceDetector, onSpeak = ::speak)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mm-${System.currentTimeMillis()}")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MirrorScreen(
    vm: MirrorViewModel,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    onSpeak: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var faceVisible by remember { mutableStateOf(false) }

    // 把抓拍能力与语音能力注入 ViewModel
    LaunchedEffect(imageCapture) {
        val capture = imageCapture ?: return@LaunchedEffect
        vm.frameProvider = { _ -> captureOne(capture, mainExecutor) }
        vm.speaker = onSpeak
    }

    // 订阅地垫事件
    LaunchedEffect(Unit) {
        vm.start()
        vm.flushPending()
        vm.matScale.events.collect { vm.onMatEvent(it) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // 镜面：摄像头预览（正对用户）
        CameraPreview(
            onCaptureReady = { imageCapture = it },
            onFaceState = { detected -> faceVisible = detected },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部状态条
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(16.dp)
        ) {
            Text("魔镜健康", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(state.message, color = Color(0xFF9FE1CB), fontSize = 14.sp)
            state.matName?.let {
                Text("地垫：$it", color = Color(0xFFB5D4F4), fontSize = 12.sp)
            }
        }

        // 中部：识别成功后引导刷牙
        if (state.stage == MirrorViewModel.Stage.BRUSHING) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.memberName ?: "",
                    color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "%d s".format(state.brushLeft),
                    color = Color(0xFF5DCAA5), fontSize = 64.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text("请开始刷牙，保持正对镜面", color = Color(0xFFB5D4F4), fontSize = 14.sp)
                if (!faceVisible) {
                    Spacer(Modifier.height(8.dp))
                    Text("未检测到人脸，请靠近一点", color = Color(0xFFEF9F27), fontSize = 13.sp)
                }
            }
        }

        // 底部：实测数据与结果
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(16.dp)
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                state.liveWeight?.let {
                    MetricChip("实时", "%.1f kg".format(it))
                }
                state.weight?.let {
                    MetricChip("体重", "%.1f kg".format(it))
                }
                if (state.confidence > 0) {
                    MetricChip("识别置信度", "%.0f%%".format(state.confidence * 100))
                }
            }

            state.summary?.let { summary ->
                Spacer(Modifier.height(12.dp))
                Text("健康日报", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(summary, color = Color(0xFFE6F1FB), fontSize = 14.sp, lineHeight = 22.sp)
            }

            if (state.assessments.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.assessments.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier
                                .padding(top = 6.dp, end = 8.dp)
                                .size(8.dp)
                                .background(levelColor(a.level), RoundedCornerShape(4.dp))
                        )
                        Column {
                            Text(a.title, color = Color.White, fontSize = 14.sp)
                            Text(a.conclusion, color = Color(0xFFB4B2A9), fontSize = 12.sp,
                                lineHeight = 18.sp)
                        }
                    }
                }
            }

            if (state.stage == MirrorViewModel.Stage.DONE ||
                state.stage == MirrorViewModel.Stage.FAILED
            ) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text("完成，准备下一位")
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFFB4B2A9), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

private fun levelColor(level: String): Color = when (level) {
    "alert" -> Color(0xFFE24B4A)
    "warning" -> Color(0xFFEF9F27)
    "watch" -> Color(0xFFEF9F27)
    else -> Color(0xFF5DCAA5)
}

/** 相机预览 + 实时人脸检测。检测结果用于提示用户是否正对镜面。 */
@Composable
fun CameraPreview(
    onCaptureReady: (ImageCapture) -> Unit,
    onFaceState: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build()
                )

                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector.process(input)
                        .addOnSuccessListener { faces -> onFaceState(faces.isNotEmpty()) }
                        .addOnCompleteListener { proxy.close() }
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                onCaptureReady(capture)

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview, analysis, capture,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/** 抓一帧 JPEG。ImageCapture 已按 JPEG 输出，直接取 plane[0] 的字节即可。 */
private suspend fun captureOne(
    capture: ImageCapture,
    executor: java.util.concurrent.Executor,
): ByteArray? = suspendCancellableCoroutine { cont ->
    capture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = runCatching {
                    val buffer = image.planes.first().buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }.getOrNull()
                image.close()
                if (cont.isActive) cont.resume(bytes)
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resume(null)
            }
        },
    )
}
