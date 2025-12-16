package com.example.meallogger.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraService(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var defaultTargetRotation: Int = Surface.ROTATION_0

    init {
        // THINKLETデバイス用のCameraX設定を適用
        applyThinkletPatch()
    }

    /**
     * デフォルトのターゲット回転を設定
     * @param rotation Surface.ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270 のいずれか
     */
    fun setDefaultTargetRotation(rotation: Int) {
        val validRotations = listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270
        )
        if (rotation in validRotations) {
            this.defaultTargetRotation = rotation
            Log.d(TAG, "Default target rotation set to: $rotation")
        } else {
            Log.w(TAG, "Invalid rotation value: $rotation. Using current value: ${this.defaultTargetRotation}")
        }
    }

    fun startCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(defaultTargetRotation)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(defaultTargetRotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                Log.d(TAG, "Camera configured successfully with rotation: $defaultTargetRotation")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun takePhoto(): File = suspendCoroutine { continuation ->
        val imageCapture = imageCapture ?: run {
            continuation.resumeWithException(IllegalStateException("Camera not initialized"))
            return@suspendCoroutine
        }

        val photoFile = File(
            context.cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved: ${photoFile.absolutePath}")

                    // 画像の回転処理をバックグラウンドで実行
                    Thread {
                        try {
                            val rotationDegrees = when (defaultTargetRotation) {
                                Surface.ROTATION_0 -> 0f
                                Surface.ROTATION_90 -> 90f
                                Surface.ROTATION_180 -> 180f
                                Surface.ROTATION_270 -> 270f
                                else -> 0f
                            }

                            // 画像を読み込み
                            var bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)

                            // 画像サイズを制限（5MB制限対策）
                            val maxDimension = 1920
                            if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                                val scale = Math.min(
                                    maxDimension.toFloat() / bitmap.width,
                                    maxDimension.toFloat() / bitmap.height
                                )
                                val newWidth = (bitmap.width * scale).toInt()
                                val newHeight = (bitmap.height * scale).toInt()
                                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                                bitmap.recycle()
                                bitmap = resizedBitmap
                                Log.d(TAG, "Image resized to ${newWidth}x${newHeight}")
                            }

                            // 回転処理
                            val finalBitmap = if (rotationDegrees != 0f) {
                                val matrix = Matrix()
                                matrix.setRotate(rotationDegrees)
                                val rotatedBitmap = Bitmap.createBitmap(
                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                )
                                bitmap.recycle()
                                Log.d(TAG, "Photo rotated by $rotationDegrees degrees")
                                rotatedBitmap
                            } else {
                                bitmap
                            }

                            // 圧縮して保存（品質80%）
                            FileOutputStream(photoFile).use { out ->
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            finalBitmap.recycle()

                            val fileSizeMB = photoFile.length() / (1024f * 1024f)
                            Log.d(TAG, "Final image size: ${"%.2f".format(fileSizeMB)} MB")

                            continuation.resume(photoFile)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to rotate photo", e)
                            continuation.resumeWithException(e)
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    /**
     * THINKLETデバイス用のCameraX設定パッチを適用
     */
    private fun applyThinkletPatch() {
        if (patchApplied) return

        if (Build.MODEL.contains("THINKLET")) {
            try {
                ProcessCameraProvider.configureInstance(
                    CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                        .setMinimumLoggingLevel(Log.WARN)
                        .build()
                )
                patchApplied = true
                Log.d(TAG, "THINKLET CameraX patch applied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply THINKLET CameraX patch", e)
            }
        }
    }

    companion object {
        private const val TAG = "CameraService"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private var patchApplied = false
    }
}
