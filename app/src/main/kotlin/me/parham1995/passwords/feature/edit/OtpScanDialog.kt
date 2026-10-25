package me.parham1995.passwords.feature.edit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.parham1995.passwords.store.OtpQr
import me.parham1995.passwords.ui.icon.LucideIcon
import java.util.concurrent.Executors

/**
 * Reads the QR code a site shows when two-factor authentication is turned on,
 * and hands back its `otpauth://totp/...` URI. The camera is the usual way;
 * an image is the way when the site is open on this same phone, so the QR
 * code is a screenshot. Frames and images are decoded here by zxing and go
 * nowhere else; a QR code that is not a usable TOTP code is said, not saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpScanDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var problem by remember { mutableStateOf<String?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    fun accept(text: String) {
        when (val result = OtpQr.read(text)) {
            is OtpQr.Result.Usable -> onScanned(result.uri)
            is OtpQr.Result.Unusable -> problem = result.reason
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val text = withContext(Dispatchers.Default) { QrReader.fromImage(context, uri) }
                    if (text == null) problem = "No QR code found in that image." else accept(text)
                }
            }
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scan 2FA QR code") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (granted) {
                        CameraScanner(onText = ::accept, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(
                            "Camera access is off. Allow it in the system settings, or read the QR code from an image.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Point the camera at the QR code the site shows when you turn on two-factor authentication.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LucideIcon("image", size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Text("Read from a screenshot", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The back camera's preview, with every frame offered to zxing until the dialog closes. */
@Composable
private fun CameraScanner(
    onText: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val view = remember { PreviewView(context) }
    LaunchedEffect(owner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        var last: String? = null
        analysis.setAnalyzer(executor) { image ->
            val text = image.use(QrReader::fromFrame)
            // The same code is in many frames in a row; report it once.
            if (text != null && text != last) {
                last = text
                main.execute { latest(text) }
            }
        }
        try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private object QrReader {
    /** Large enough for a full-screen screenshot's QR code, small enough to decode quickly. */
    private const val MAX_SIDE = 2048

    private val hints =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            // A site in dark mode can draw its code light on dark.
            DecodeHintType.ALSO_INVERTED to true,
        )

    /** The luminance plane of a YUV frame is all a QR code needs. */
    fun fromFrame(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        // The last row can stop short of the row stride; pad it so zxing sees a full rectangle.
        val bytes = ByteArray(plane.rowStride * image.height)
        buffer.get(bytes, 0, minOf(buffer.remaining(), bytes.size))
        return decode(
            PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false),
        )
    }

    fun fromImage(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap: Bitmap =
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_SIDE) decoder.setTargetSampleSize((longest + MAX_SIDE - 1) / MAX_SIDE)
                }
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()

    private fun decode(source: LuminanceSource): String? =
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        }
}
