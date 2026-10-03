package com.samrat.cardboardhands

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis

/**
 * Low-load camera mode for hand tracking: a small analysis frame and a locked frame rate.
 * Fewer pixels means less YUV->Bitmap conversion, less rotation and less MediaPipe/YOLO work,
 * which is what heats up mid-range phones.
 *
 * The switch lives in its own preferences file, read with MODE_MULTI_PROCESS because the
 * tracking service runs in the ":hands" process and must see changes made in the main app.
 */
object CameraQuality {
    /** What a fresh install gets. Set to false to ship the original high-res behaviour by default. */
    const val DEFAULT_ENABLED = true

    /** Analysis frame in low mode. Try Size(320, 240) for the lowest load (hands far away get jumpier). */
    val LOW_SIZE = Size(640, 480)

    /** Frame rate ceiling in low mode; the nearest range the camera really supports is used. */
    const val TARGET_FPS = 30

    private const val PREFS = "phonexr_camera"
    private const val KEY_LOW = "low_res"

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_LOW, DEFAULT_ENABLED)

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_LOW, on).apply()

    /** Size to analyse: [LOW_SIZE] in low mode, otherwise whatever the caller used before. */
    fun size(context: Context, normal: Size): Size = if (enabled(context)) LOW_SIZE else normal

    /** Applies the resolution (and in low mode the fps lock) to an analysis builder. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun configure(builder: ImageAnalysis.Builder, context: Context, normal: Size): ImageAnalysis.Builder {
        builder.setTargetResolution(size(context, normal))
        if (enabled(context)) {
            fpsRange(context)?.let {
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
        }
        return builder
    }

    /** The tightest AE range at or below [TARGET_FPS] that the back camera supports, e.g. [30,30]. */
    private fun fpsRange(context: Context): Range<Int>? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return@runCatching null
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper <= TARGET_FPS && it.upper >= 15 }
            ?.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
    }.getOrNull()
}
