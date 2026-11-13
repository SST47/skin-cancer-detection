package com.skinly.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class   CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null  // ← save reference for torch

    fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(  // ← capture return value
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun torchOn()  { camera?.cameraControl?.enableTorch(true) }
    fun torchOff() { camera?.cameraControl?.enableTorch(false) }

    suspend fun capturePhoto(): Bitmap = suspendCancellableCoroutine { cont ->
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture?.takePicture(
            opts,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    cont.resume(Bitmap.createScaledBitmap(bmp, 224, 224, true))
                }
                override fun onError(e: ImageCaptureException) {
                    cont.resumeWithException(e)
                }
            }
        )
    }
}
