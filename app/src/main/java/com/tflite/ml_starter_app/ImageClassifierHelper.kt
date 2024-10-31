package com.tflite.ml_starter_app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import org.tensorflow.lite.DataType
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.core.vision.ImageProcessingOptions
import org.tensorflow.lite.task.gms.vision.TfLiteVision
import org.tensorflow.lite.task.gms.vision.classifier.Classifications
import org.tensorflow.lite.task.gms.vision.classifier.ImageClassifier

class ImageClassifierHelper(
    private var threshold: Float = 0.1f,
    private var maxResults: Int = 3,
    private val modelName: String = "mobilenet_v1.tflite",
    private val context: Context,
    private val classifierListener: ClassifierListener?
) {
    // Initialization object of ImageClassifier
    private var imageClassifier: ImageClassifier? = null
    init {
        // GPU Delegate adalah driver yang memungkinkan TensorFlow Lite untuk mengakses hardware GPU.
        TfLiteGpu.isGpuDelegateAvailable(context).onSuccessTask { gpuAvailable ->
            val optionsBuilder = TfLiteInitializationOptions.builder()
            if (gpuAvailable) {
                optionsBuilder.setEnableGpuDelegateSupport(true) // Untuk mengaktifkan GPU Delegate
            }
            // inisialisasi
            TfLiteVision.initialize(context, optionsBuilder.build())
        }.addOnSuccessListener {
            setupImageClassifier()
        }.addOnFailureListener {
            classifierListener?.onError(context.getString(R.string.tflitevision_is_not_initialized_yet))
        }
    }

    /*Setup Configuration with
     - ImageClassifierOption
     - BaseOption
    */
    private fun setupImageClassifier() {
        val optionBuilder = ImageClassifier.ImageClassifierOptions.builder()
            // menentukan batas minimal keakuratan dari hasil yang ditampilkan. 0,1 artinya 10%
            .setScoreThreshold(threshold)
            // menentukan batas maksimal jumlah yang dihasilkan
            .setMaxResults(maxResults)
        val baseOptionsBuilder = BaseOptions.builder()

            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                baseOptionsBuilder.useGpu()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1){
                baseOptionsBuilder.useNnapi()
            } else {
                // Menggunakan CPU
                baseOptionsBuilder.setNumThreads(4)
            }

        // menentukan jumlah thread yang digunakan untuk melakukan referensi
        // .setNumThreads(4)
        optionBuilder.setBaseOptions(baseOptionsBuilder.build())

        /*Menyertakan Model dan Konfigurasi*/
        try {
            if (!TfLiteVision.isInitialized()) {
                val errorMessage = context.getString(R.string.tflitevision_is_not_initialized_yet)
                Log.e(TAG, errorMessage)
                classifierListener?.onError(errorMessage)
                return
            }


            // membuat image classifier berdasarkan assetfilemodel dan option yg didefinisikan sebelumnya
            imageClassifier = ImageClassifier.createFromFileAndOptions(
                context,
                modelName,
                optionBuilder.build()
            )
        } catch (e: IllegalStateException) {
            classifierListener?.onError(context.getString(R.string.image_classifier_failed))
            Log.e(TAG, e.message.toString())
        }

    }

    fun classifyImage(image: ImageProxy) {
        if (imageClassifier == null) {
            setupImageClassifier()
        }

        /* Image Processor
        Disini menyiapkan image processor untuk melakukan preprocessing pada gambar
        sesuai dengan metadata pada model menggunakan ukuran 224x224 dan tipe data UINT8
        preprocessing itu sendiri merupakan persiapan sebelum gambar diproses supaya sesuai dengan model,
        seperti mengubah ukuran, memutar, dan mengkonversi gambar.
        */
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224,224,ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
            .add(CastOp(DataType.UINT8))
            .build()
        /*
        TensorImage.fromBitmap()
        - digunakan untuk mengonversi Bitmap menjadi TensorImage
        Untuk fungsi toBitmap(image)
        - digunakan untuk mengonversi ImageProxy menjadi Bitmap terlebih dahulu
        */
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(toBitmap(image)))

        /* ImageProcessingOptions
        - untuk mengatur orientasi gambar supaya sesuai dengan gambar pada model.
        */
        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setOrientation(getOrientationFromRotation(image.imageInfo.rotationDegrees))
            .build()

        var inferenceTime = SystemClock.uptimeMillis()
        val results = imageClassifier?.classify(tensorImage, imageProcessingOptions)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        classifierListener?.onResults(
            results,
            inferenceTime
        )
    }

    private fun getOrientationFromRotation(rotation: Int): ImageProcessingOptions.Orientation {
        return when (rotation) {
            Surface.ROTATION_270 -> ImageProcessingOptions.Orientation.BOTTOM_RIGHT
            Surface.ROTATION_180 -> ImageProcessingOptions.Orientation.RIGHT_BOTTOM
            Surface.ROTATION_90 -> ImageProcessingOptions.Orientation.TOP_LEFT
            else -> ImageProcessingOptions.Orientation.RIGHT_TOP
        }
    }

    private fun toBitmap(image: ImageProxy): Bitmap {

        val bitmapBuffer = Bitmap.createBitmap(
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        image.close()
        return bitmapBuffer
    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(results: List<Classifications>?, inferenceTime: Long)
    }

    companion object {
        private const val TAG = "ImageClassifierHelper"
    }
}