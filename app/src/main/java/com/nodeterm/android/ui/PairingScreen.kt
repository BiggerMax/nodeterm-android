package com.nodeterm.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nodeterm.android.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Scan a QR code or paste code text. Hands the RAW decoded text up (the ViewModel decides
 * whether it is a flat `nodeterm://pair?code=…` offer or a v0.2.37 host payload).
 */
@Composable
fun PairingScreen(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var pasted by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "nodeterm",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.pair_with_your_host),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        if (cameraGranted) {
            QrScanView(onDecoded = { code -> onCode(code) })
        } else {
            // Camera denied — never a dead end: retry the grant or jump to system settings, and
            // the paste box below still works without a camera at all.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_off),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.try_again), fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.open_settings), fontSize = 12.sp) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.or_paste_pairing_code),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.pairing_code_placeholder)) },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onCode(pasted.trim()) },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.connect))
        }

        Spacer(Modifier.height(16.dp))
        PairingSteps()
        Spacer(Modifier.height(8.dp))
    }
}

/** Three-step pairing guide — mirrors the desktop flow (show QR → scan/paste → compare SAS). */
@Composable
private fun PairingSteps() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        StepRow(1, stringResource(R.string.step_on_host), stringResource(R.string.step_on_host_detail))
        Spacer(Modifier.height(8.dp))
        StepRow(2, stringResource(R.string.step_on_this_phone), stringResource(R.string.step_on_this_phone_detail))
        Spacer(Modifier.height(8.dp))
        StepRow(3, stringResource(R.string.step_compare_codes), stringResource(R.string.step_compare_codes_detail))
    }
}

@Composable
private fun StepRow(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Full-screen camera preview that continuously decodes QR codes. */
@Composable
private fun QrScanView(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
                )
            )
        }
    }
    // The analyzer runs on a CameraX background thread — callbacks MUST hop to the main thread
    // (navigation/Compose state are main-thread-only). The same code is also deduped so a
    // non-navigating (bad) QR does not re-fire an error snackbar on every frame.
    var lastCode by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { proxy ->
                    val code = decodeQr(reader, proxy)
                    proxy.close()
                    if (code != null && code != lastCode) {
                        lastCode = code
                        mainHandler.post { onDecoded(code) }
                    }
                }
            }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            cameraProviderFuture.get().unbindAll()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    val provider = cameraProviderFuture.get()
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black, RoundedCornerShape(16.dp))
        )
        // Visible scan window: scrims darken the frame outside a centred 220dp window, a crisp
        // border marks the scan area, and a hint says what to point at — desktop/iOS camera style.
        ScanOverlay()
    }
}

/** Scrim + scan-window frame + "point at the QR" hint over the live camera preview. */
@Composable
private fun ScanOverlay() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val win = 220.dp
        val side = (maxWidth - win) / 2
        val topH = (maxHeight - win) / 2
        val scrim = Color.Black.copy(alpha = 0.45f)
        Box(Modifier.align(Alignment.TopCenter).width(maxWidth).height(topH).background(scrim))
        Box(Modifier.align(Alignment.BottomCenter).width(maxWidth).height(topH).background(scrim))
        Box(Modifier.align(Alignment.CenterStart).width(side).height(win).background(scrim))
        Box(Modifier.align(Alignment.CenterEnd).width(side).height(win).background(scrim))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(win)
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
        )
        Text(
            stringResource(R.string.point_at_qr),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        )
    }
}

private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val y = ByteArray(buffer.remaining()).also { buffer.get(it) }
    // Some devices pad each Y row (rowStride > width) or interleave pixels (pixelStride > 1);
    // PlanarYUVLuminanceSource assumes a tight width×height layout, so re-pack the plane first.
    val data: ByteArray = if (rowStride == width && pixelStride == 1) {
        y
    } else {
        ByteArray(width * height).also { out ->
            for (row in 0 until height) {
                val src = row * rowStride
                for (col in 0 until width) {
                    out[row * width + col] = y[src + col * pixelStride]
                }
            }
        }
    }
    val source = PlanarYUVLuminanceSource(
        data, width, height, 0, 0, width, height, false
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

/** Shown while the relay handshake is running. */
@Composable
fun ConnectingView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.connecting_to_host), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The SAS screen: both humans compare this 6-digit code before the host approves. */
@Composable
fun SasScreen(sas: String, hostLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = sas,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.compare_this_code),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.compare_code_body, hostLabel),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codes_match_connect))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }
}
