package ru.receipt.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import ru.receipt.core.OperationType
import ru.receipt.core.ParsedText
import ru.receipt.core.QrData
import ru.receipt.core.QrResult
import ru.receipt.core.ReconcileStatus
import ru.receipt.core.parseQr
import ru.receipt.core.parseReceiptText
import ru.receipt.core.reconcile
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var previewView: PreviewView
    private lateinit var cameraButton: Button
    private lateinit var qrView: TextView
    private lateinit var textView: TextView
    private lateinit var statusView: TextView
    private lateinit var rawView: TextView
    private var imageCapture: ImageCapture? = null
    private var qrData: QrData? = null
    private var parsedText: ParsedText? = null
    private var rawLines: List<String> = emptyList()
    private var lastQrRaw: String? = null
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            cameraButton.isEnabled = false
            qrView.text = "Камера недоступна. Разрешение можно выдать в настройках; фото из галереи доступно без него."
        }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) analyzePhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        fun heading(value: String): TextView = TextView(this).apply {
            text = value; textSize = 21f; setTextColor(Color.rgb(25, 45, 65)); setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(heading("Сканер кассовых чеков"))
        root.addView(TextView(this).apply { text = "Наведите камеру на QR, затем сфотографируйте весь чек. Можно выбрать готовое фото."; textSize = 15f })
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(12) })
        cameraButton = Button(this).apply { text = "Сфотографировать чек"; isEnabled = false; setOnClickListener { capturePhoto() } }
        root.addView(cameraButton)
        root.addView(Button(this).apply { text = "Выбрать фото из галереи"; setOnClickListener { galleryLauncher.launch("image/*") } })
        root.addView(Button(this).apply { text = "Новый чек"; setOnClickListener {
            qrData = null; parsedText = null; rawLines = emptyList(); lastQrRaw = null; rawView.visibility = View.GONE; renderResult()
        } })
        root.addView(heading("QR-код"))
        qrView = TextView(this).apply { textSize = 16f }
        root.addView(qrView)
        root.addView(heading("Распознанный чек"))
        textView = TextView(this).apply { textSize = 16f }
        root.addView(textView)
        statusView = TextView(this).apply { textSize = 19f; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(statusView)
        rawView = TextView(this).apply { textSize = 13f; visibility = View.GONE; setTextIsSelectable(true) }
        root.addView(Button(this).apply { text = "Показать / скрыть сырой текст OCR"; setOnClickListener {
            rawView.visibility = if (rawView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        } })
        root.addView(rawView)
        setContentView(ScrollView(this).apply { addView(root) })
        renderResult()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes -> codes.firstOrNull { it.rawValue != null }?.rawValue?.let(::onQrFound) }
                        .addOnCompleteListener { proxy.close() }
                }
                val capture = ImageCapture.Builder().build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                imageCapture = capture
                cameraButton.isEnabled = true
            } catch (error: Exception) {
                cameraButton.isEnabled = false
                qrView.text = "Не удалось открыть камеру: ${error.localizedMessage ?: "неизвестная ошибка"}. Выберите фото из галереи."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQrFound(raw: String) {
        runOnUiThread {
            if (raw == lastQrRaw) return@runOnUiThread
            lastQrRaw = raw
            when (val result = parseQr(raw)) {
                is QrResult.Success -> { qrData = result.data; renderResult() }
                QrResult.NotAReceipt -> qrView.text = "Найден QR-код, но это не кассовый чек."
                is QrResult.MissingField -> qrView.text = "В QR-коде отсутствует поле ${result.name}."
                is QrResult.InvalidField -> qrView.text = "Некорректное поле ${result.name} в QR-коде."
            }
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File.createTempFile("receipt-", ".jpg", cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        cameraButton.isEnabled = false
        capture.takePicture(options, worker, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                runOnUiThread { cameraButton.isEnabled = true; analyzePhoto(Uri.fromFile(file), file) }
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                runOnUiThread { cameraButton.isEnabled = true; statusView.text = "Не удалось сделать фото: ${exception.localizedMessage}" }
            }
        })
    }

    private fun analyzePhoto(uri: Uri, temporaryFile: File? = null) {
        val image = try { InputImage.fromFilePath(this, uri) }
            catch (error: Exception) { statusView.text = "Не удалось открыть изображение: ${error.localizedMessage}"; temporaryFile?.delete(); return }
        statusView.text = "Распознавание фото…"
        // The same gallery image can provide both the QR and OCR, so a physical camera is optional.
        scanner.process(image)
            .addOnSuccessListener { codes -> codes.firstOrNull { it.rawValue != null }?.rawValue?.let(::onQrFound) }
            .addOnFailureListener { qrView.text = "Ошибка распознавания QR на фото: ${it.localizedMessage}" }
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                rawLines = result.textBlocks.flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
                    .map { it.text }
                parsedText = parseReceiptText(rawLines)
                rawView.text = if (rawLines.isEmpty()) "Текст не распознан." else rawLines.joinToString("\n")
                renderResult()
            }
            .addOnFailureListener { statusView.text = "Ошибка OCR: ${it.localizedMessage ?: "неизвестная ошибка"}" }
            .addOnCompleteListener { temporaryFile?.delete() }
    }

    private fun renderResult() {
        val qr = qrData
        qrView.text = if (qr == null) "QR чека пока не распознан." else buildString {
            append("Дата: ${qr.dateTime.format(dateFormat)}\n")
            append("Сумма: ${money(qr.sumKopecks)} ₽\n")
            append("Тип: ${operationLabel(qr.operationType)}\n")
            append("ФН: ${qr.fn}; ФД: ${qr.documentNumber}; ФП: ${qr.fiscalSign}")
        }
        val parsed = parsedText
        textView.text = if (parsed == null) "Фото пока не распознано." else buildString {
            append("Итог OCR: ${parsed.totalKopecks?.let { "${money(it)} ₽" } ?: "не найден"}\n")
            append("Дата OCR: ${parsed.dateTime?.format(dateFormat) ?: "не найдена"}\n")
            append("Позиции: ${parsed.items.size}\n")
            parsed.items.forEachIndexed { index, item ->
                append("${index + 1}. ${item.name}\n")
                append("   ${item.quantity.toPlainString()} × ${money(item.priceKopecks)} = ${money(item.amountKopecks)} ₽\n")
            }
        }
        if (parsed == null) { statusView.text = "Ожидание фото чека"; statusView.setTextColor(Color.DKGRAY); return }
        val status = reconcile(qr, parsed)
        statusView.text = when (status) {
            ReconcileStatus.OK -> "Всё сходится: итог OCR, позиции и QR совпадают."
            ReconcileStatus.UNVERIFIED -> "Позиции сходятся с итогом, но нет QR: итог не подтверждён."
            ReconcileStatus.TOTAL_MISMATCH -> "Не сходится итог OCR с суммой из QR."
            ReconcileStatus.ITEMS_MISMATCH -> "Не сходится сумма позиций: возможно, позиции потеряны."
            ReconcileStatus.NO_TOTAL -> "Итог не найден: сверка невозможна."
        }
        statusView.setTextColor(if (status == ReconcileStatus.OK) Color.rgb(20, 120, 65) else Color.rgb(175, 65, 25))
    }

    private fun money(kopecks: Long) = "${kopecks / 100}.${(kopecks % 100).toString().padStart(2, '0')}"
    private fun operationLabel(value: OperationType) = when (value) {
        OperationType.INCOME -> "Приход"
        OperationType.INCOME_RETURN -> "Возврат прихода"
        OperationType.EXPENSE -> "Расход"
        OperationType.EXPENSE_RETURN -> "Возврат расхода"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        textRecognizer.close()
        worker.shutdown()
    }
}
