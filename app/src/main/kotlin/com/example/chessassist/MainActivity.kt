package com.example.chessassist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen(vm) } }
    }
}

@Composable
private fun Screen(vm: GameViewModel) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    // Screen mode: ask for notification permission (Android 13+), then for screen capture, then start the service.
    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(ctx, ScreenService::class.java)
                .putExtra("code", r.resultCode)
                .putExtra("data", data)
                .putExtra("white", state.playingWhite)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
    val startScreen = {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startScreen() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (granted) CameraWithGuide(onFrame = vm::onFrame, intervalMs = { vm.frameIntervalMs })
            else Text("Camera permission needed", Modifier.align(Alignment.Center))
        }

        Text(state.status, style = MaterialTheme.typography.titleMedium)
        state.suggestion?.let { Text(it, style = MaterialTheme.typography.headlineMedium, color = Color(0xFF7CFC9A)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.playingWhite, onClick = { vm.setColor(true) }, label = { Text("I'm White") })
            FilterChip(selected = !state.playingWhite, onClick = { vm.setColor(false) }, label = { Text("I'm Black") })
            Spacer(Modifier.weight(1f))
            if (state.running) Button(onClick = vm::stop) { Text("Stop") }
            else Button(onClick = vm::start, enabled = granted) { Text("Start") }
        }

        if (!state.running) {
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startScreen()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Screen mode: watch this phone's screen (needs OpenRouter key)") }

            var key by remember { mutableStateOf(vm.apiKey) }
            var model by remember { mutableStateOf(vm.model) }
            OutlinedTextField(
                value = key, onValueChange = { key = it; vm.apiKey = it },
                label = { Text("OpenRouter API key (blank = use on-device model)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; vm.model = it },
                label = { Text("Vision model") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        BoardWithArrow(state.suggestionUci, flipped = !state.playingWhite, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    }
}

/** Camera preview with a square frame; the analyzer crops to the frame's area (center square). */
@Composable
private fun CameraWithGuide(onFrame: (Bitmap) -> Unit, intervalMs: () -> Long) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var last by remember { mutableLongStateOf(0L) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - last >= intervalMs()) { last = now; onFrame(proxy.toSquareBitmap()) }
                    proxy.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(c))
            view
        })
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            drawRect(Color.White.copy(alpha = 0.8f), Offset((size.width - s) / 2, (size.height - s) / 2), Size(s, s), style = Stroke(3f))
        }
    }
}

/** Upright, centered square crop of the camera frame (matches the on-screen guide). */
private fun ImageProxy.toSquareBitmap(): Bitmap {
    val src = toBitmap()
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }, true)
    val s = minOf(rotated.width, rotated.height)
    return Bitmap.createBitmap(rotated, (rotated.width - s) / 2, (rotated.height - s) / 2, s, s)
}

@Composable
private fun BoardWithArrow(uci: String?, flipped: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val sq = size.minDimension / 8f
        for (r in 0 until 8) for (f in 0 until 8) {
            drawRect(
                if ((r + f) % 2 == 0) Color(0xFFEEEED2) else Color(0xFF769656),
                Offset(f * sq, r * sq), Size(sq, sq),
            )
        }
        if (uci != null && uci.length >= 4) {
            fun center(s: String): Offset {
                var f = s[0] - 'a'; var rank = s[1] - '1'
                var row = 7 - rank
                if (flipped) { f = 7 - f; row = 7 - row }
                return Offset((f + 0.5f) * sq, (row + 0.5f) * sq)
            }
            drawLine(Color(0xCCFF8800), center(uci.substring(0, 2)), center(uci.substring(2, 4)), strokeWidth = sq * 0.22f, cap = StrokeCap.Round)
        }
    }
}
