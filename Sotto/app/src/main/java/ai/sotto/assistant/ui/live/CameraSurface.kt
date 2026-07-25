package ai.sotto.assistant.ui.live

import ai.sotto.assistant.core.SLog
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.util.Size
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The camera viewfinder plus the frame tap that feeds the face pipeline.
 *
 * Design Doc 1 § Face Detection Pipeline calls for continuous analysis "at thirty frames
 * per second". [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] is what makes that safe: if a
 * frame takes longer than 33 ms to process, the next ones are dropped rather than
 * queued, so the pipeline always works on what the camera is seeing *now* instead of
 * falling further and further behind.
 *
 * Analysis runs at a deliberately modest resolution — face detection does not need
 * megapixels, and a smaller frame is a faster frame.
 */
@Composable
fun CameraSurface(
    onFrame: (Bitmap, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    onError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                provider = providerFuture.get()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                // Preview and analysis must share an aspect ratio, or the face overlay
                // (which maps analysis coordinates onto the preview) lands off the face.
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(executor, FrameAnalyzer(onFrame)) }

                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview,
                    analysis,
                )
            } catch (t: Throwable) {
                SLog.e(TAG, "Could not start the camera", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Converts each frame to an upright RGB bitmap and hands it to the pipeline.
 *
 * The bitmap is recycled as soon as the callback returns, so downstream code must not
 * hold on to it — [ai.sotto.assistant.vision.FacePipeline] copies anything it keeps.
 */
private class FrameAnalyzer(
    private val onFrame: (Bitmap, Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation == 0) {
                raw
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { if (it !== raw && !raw.isRecycled) raw.recycle() }
            }
            try {
                onFrame(upright, upright.width, upright.height)
            } finally {
                if (!upright.isRecycled) upright.recycle()
            }
        } catch (t: Throwable) {
            SLog.w(TAG, "Dropping a frame that could not be converted", t)
        } finally {
            image.close()
        }
    }
}

private const val TAG = "CameraSurface"

/** 480x640 in portrait — plenty for BlazeFace at conversational distance. */
const val ANALYSIS_WIDTH = 480
const val ANALYSIS_HEIGHT = 640
