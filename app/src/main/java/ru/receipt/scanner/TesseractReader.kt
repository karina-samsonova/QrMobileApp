package ru.receipt.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.IOException

/** Called only from the single OCR executor; Tesseract is not thread safe. */
class TesseractReader(private val context: Context) {
    private var api: TessBaseAPI? = null

    private fun engine(): TessBaseAPI {
        api?.let { return it }
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        if (!data.exists() && !data.mkdirs()) throw IOException("Не удалось создать папку языковых моделей")
        for (language in listOf("rus", "eng")) {
            val target = File(data, "$language.traineddata")
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("tessdata/$language.traineddata").use { source ->
                    target.outputStream().use { targetStream -> source.copyTo(targetStream) }
                }
            }
        }
        val created = TessBaseAPI()
        if (!created.init(root.absolutePath, "rus+eng")) {
            created.recycle()
            throw IOException("Не удалось загрузить русскую и английскую модели Tesseract")
        }
        api = created
        return created
    }

    fun recognize(uri: Uri): List<String> {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val decoded = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: throw IOException("Изображение не открывается")
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true).also { decoded.recycle() }
        val scale = if (oriented.width < 800) 3 else 1
        val bitmap = if (scale == 1) oriented else Bitmap.createScaledBitmap(oriented, oriented.width * scale, oriented.height * scale, true).also { oriented.recycle() }
        return try {
            val tess = engine()
            tess.setImage(bitmap)
            tess.utF8Text.orEmpty().lines().map(String::trim).filter(String::isNotEmpty)
        } finally { bitmap.recycle() }
    }

    fun close() { api?.recycle(); api = null }
}
