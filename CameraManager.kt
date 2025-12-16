package ai.fd.mealtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 APIを使用したカメラマネージャー
 * THINKLETなど画面なし端末でも動作
 */
class CameraManager(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

    private val backgroundThread = HandlerThread("CameraBackground").also { it.start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    companion object {
        private const val TAG = "CameraManager"
        private const val IMAGE_WIDTH = 1920
        private const val IMAGE_HEIGHT = 1080
    }

    /**
     * カメラの権限をチェック
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 写真を撮影（suspend関数）
     *
     * @param countdown カウントダウンを行うか
     * @return 撮影した画像ファイル
     */
    suspend fun takePhoto(
        countdown: Boolean = true,
        onCountdown: ((Int) -> Unit)? = null
    ): File = suspendCancellableCoroutine { continuation ->

        try {
            // カウントダウン
            if (countdown && onCountdown != null) {
                for (i in 3 downTo 1) {
                    onCountdown(i)
                    Thread.sleep(1000)
                }
            }

            // カメラを開く
            openCamera { device ->
                cameraDevice = device

                // ImageReaderをセットアップ
                imageReader = ImageReader.newInstance(
                    IMAGE_WIDTH,
                    IMAGE_HEIGHT,
                    ImageFormat.JPEG,
                    1
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            // ファイルに保存
                            val photoFile = createImageFile()
                            FileOutputStream(photoFile).use { output ->
                                output.write(bytes)
                            }

                            Log.i(TAG, "Photo saved: ${photoFile.absolutePath}")
                            continuation.resume(photoFile)

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save image", e)
                            continuation.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }, backgroundHandler)
                }

                // キャプチャセッションを作成
                device.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session

                            // 撮影リクエストを作成
                            val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader!!.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }.build()

                            // 撮影実行
                            session.capture(captureRequest, null, backgroundHandler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure capture session")
                            continuation.resumeWithException(Exception("Camera configuration failed"))
                        }
                    },
                    backgroundHandler
                )
            }

            // キャンセル時のクリーンアップ
            continuation.invokeOnCancellation {
                closeCamera()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo", e)
            continuation.resumeWithException(e)
        }
    }

    /**
     * カメラを開く
     */
    private fun openCamera(onOpened: (CameraDevice) -> Unit) {
        if (!hasPermission()) {
            throw SecurityException("Camera permission not granted")
        }

        val cameraId = getCameraId() ?: throw Exception("No camera found")

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened: $cameraId")
                    onOpened(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
            throw e
        }
    }

    /**
     * 背面カメラのIDを取得
     */
    private fun getCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to get camera ID", e)
            null
        }
    }

    /**
     * 画像ファイルを作成
     */
    private fun createImageFile(): File {
        val timestamp = System.currentTimeMillis()
        val storageDir = context.getExternalFilesDir("meals")
        storageDir?.mkdirs()
        return File(storageDir, "meal_$timestamp.jpg")
    }

    /**
     * カメラをクローズ
     */
    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            Log.i(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    /**
     * リソースを解放
     */
    fun release() {
        closeCamera()
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted", e)
        }
        Log.i(TAG, "Camera manager released")
    }
}
