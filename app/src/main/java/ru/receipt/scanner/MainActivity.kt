package ru.receipt.scanner

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
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
import java.util.concurrent.atomic.AtomicInteger

private data class OcrMeasurement(
    val engine: String,
    val lines: List<String>,
    val elapsedMs: Long,
    val error: String? = null,
) {
    val parsed: ParsedText? = if (error == null) parseReceiptText(lines) else null
}

class MainActivity : ComponentActivity() {
    private val cameraWorker = Executors.newSingleThreadExecutor()
    private val ocrWorker = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private val mlKit = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var tesseract: TesseractReader? = null
    private lateinit var previewView: PreviewView
    private lateinit var overlay: RecognitionOverlay
    private lateinit var cameraButton: Button
    private lateinit var engineView: TextView
    private lateinit var sourceView: TextView
    private lateinit var qrView: TextView
    private lateinit var receiptView: TextView
    private lateinit var statusView: TextView
    private lateinit var saveView: TextView
    private lateinit var comparisonView: TextView
    private lateinit var rawView: TextView
    private var imageCapture: ImageCapture? = null
    private var qrData: QrData? = null
    private var parsedText: ParsedText? = null
    private var rawLines: List<String> = emptyList()
    private var lastQrRaw: String? = null
    private var lastLiveQrRaw: String? = null
    private var lastLiveQrAt = 0L
    private var selectedEngine = "Tesseract"
    private val measurements = mutableMapOf<String, OcrMeasurement>()
    private var photoGeneration = 0
    @Volatile private var photoPending = false
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            cameraButton.isEnabled = false
            qrView.text = "Нет доступа к камере. Фото из галереи можно обработать без разрешения."
        }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) { resetReceipt(); analyzePhoto(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        selectedEngine = getSharedPreferences("scanner", MODE_PRIVATE).getString("engine", "Tesseract") ?: "Tesseract"
        if (::engineView.isInitialized) {
            engineView.text = "Основной OCR: $selectedEngine"
            measurements[selectedEngine]?.let(::selectMeasurement)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        fun heading(value: String) = TextView(this).apply {
            text = value; textSize = 21f; setTextColor(Color.rgb(25, 45, 65)); setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(heading("Сканер кассовых чеков"))
        root.addView(TextView(this).apply { text = "Наведите камеру на QR, затем сфотографируйте чек или выберите фото. Зелёная рамка — QR, жёлтая — строка итога."; textSize = 15f })
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        overlay = RecognitionOverlay(this)
        val previewContainer = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(this@MainActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(12) })
        cameraButton = Button(this).apply { text = "Сфотографировать чек"; isEnabled = false; setOnClickListener { capturePhoto() } }
        root.addView(cameraButton)
        root.addView(Button(this).apply { text = "Выбрать фото из галереи"; setOnClickListener { galleryLauncher.launch("image/*") } })
        root.addView(Button(this).apply { text = "Открыть образец чека"; setOnClickListener { chooseSample() } })
        root.addView(Button(this).apply { text = "Новый чек"; setOnClickListener { resetReceipt() } })
        root.addView(Button(this).apply { text = "История чеков"; setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } })
        root.addView(Button(this).apply { text = "Настройки OCR"; setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } })
        engineView = TextView(this).apply { textSize = 15f }
        root.addView(engineView)
        sourceView = TextView(this).apply { textSize = 15f }
        root.addView(sourceView)
        root.addView(heading("QR-код"))
        qrView = TextView(this).apply { textSize = 16f }
        root.addView(qrView)
        root.addView(heading("Распознанный чек"))
        receiptView = TextView(this).apply { textSize = 16f }
        root.addView(receiptView)
        statusView = TextView(this).apply { textSize = 19f; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(statusView)
        saveView = TextView(this).apply { textSize = 14f }
        root.addView(saveView)
        root.addView(heading("Сравнение OCR-движков"))
        comparisonView = TextView(this).apply { textSize = 15f }
        root.addView(comparisonView)
        rawView = TextView(this).apply { textSize = 13f; visibility = View.GONE; setTextIsSelectable(true) }
        root.addView(Button(this).apply { text = "Показать / скрыть сырой текст OCR"; setOnClickListener { rawView.visibility = if (rawView.visibility == View.VISIBLE) View.GONE else View.VISIBLE } })
        root.addView(rawView)
        setContentView(ScrollView(this).apply { addView(root) })
        engineView.text = "Основной OCR: $selectedEngine"
        resetReceipt()
    }

    private fun resetReceipt() {
        qrData = null; parsedText = null; rawLines = emptyList(); lastQrRaw = null
        lastLiveQrRaw = null; lastLiveQrAt = 0L
        measurements.clear(); photoGeneration++; photoPending = false
        overlay.clear(); rawView.text = ""; rawView.visibility = View.GONE
        saveView.text = ""; comparisonView.text = "Сравнение появится после обработки фото."
        sourceView.text = ""
        renderResult()
    }

    private fun chooseSample() {
        val names = arrayOf("Чек 1 — стройтовары (без QR)", "Чек 2 — продукты", "Чек 3 — шоколад", "Чек 4 — краски", "Чек 5 — зажигалки")
        val extensions = arrayOf("jpg", "png", "jpg", "png", "jpg")
        AlertDialog.Builder(this).setTitle("Предоставленные образцы")
            .setItems(names) { _, index ->
                resetReceipt()
                try {
                    val file = File.createTempFile("sample-${index + 1}-", ".${extensions[index]}", cacheDir)
                    assets.open("samples/check${index + 1}.${extensions[index]}").use { input -> file.outputStream().use(input::copyTo) }
                    sourceView.text = "Образец ${index + 1}: ${names[index]}"
                    analyzePhoto(Uri.fromFile(file), file)
                } catch (error: Exception) {
                    statusView.text = "Не удалось открыть образец: ${error.localizedMessage}"
                }
            }.show()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            previewView.post {
                try {
                    val provider = future.get()
                    val viewport = previewView.viewPort ?: throw IllegalStateException("Превью ещё не готово")
                    val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(cameraWorker) { proxy ->
                        if (photoPending) { proxy.close(); return@setAnalyzer }
                        val media = proxy.image
                        if (media == null) { proxy.close(); return@setAnalyzer }
                        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        val qrTask = scanner.process(image)
                        val textTask = mlKit.process(image)
                        qrTask.addOnSuccessListener { codes ->
                            val code = codes.firstOrNull { it.rawValue != null }
                            overlay.setQrBox(mapBox(code?.boundingBox, proxy))
                            code?.rawValue?.let { onQrFound(it, fromLiveCamera = true) }
                        }.addOnFailureListener { overlay.setQrBox(null) }
                        textTask.addOnSuccessListener { result ->
                            val totalLine = result.textBlocks.flatMap { it.lines }.firstOrNull { line ->
                                Regex("^(ИТОГ|ИТОГО|К ОПЛАТЕ)", RegexOption.IGNORE_CASE).containsMatchIn(line.text.trim())
                            }
                            overlay.setTotalBox(mapBox(totalLine?.boundingBox, proxy))
                        }.addOnFailureListener { overlay.setTotalBox(null) }
                        Tasks.whenAllComplete(qrTask, textTask).addOnCompleteListener { proxy.close() }
                    }
                    val capture = ImageCapture.Builder().build()
                    val group = UseCaseGroup.Builder().setViewPort(viewport).addUseCase(preview).addUseCase(analysis).addUseCase(capture).build()
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    imageCapture = capture
                    cameraButton.isEnabled = true
                } catch (error: Exception) {
                    cameraButton.isEnabled = false
                    qrView.text = "Не удалось открыть камеру: ${error.localizedMessage ?: "неизвестная ошибка"}. Фото из галереи доступно."
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun mapBox(rect: Rect?, proxy: ImageProxy): RectF? {
        if (rect == null) return null
        val target = previewView.outputTransform ?: return null
        val source = ImageProxyTransformFactory().apply { setUsingCropRect(true); setUsingRotationDegrees(true) }.getOutputTransform(proxy)
        return RectF(rect).also { CoordinateTransform(source, target).mapRect(it) }
    }

    private fun onQrFound(raw: String, fromLiveCamera: Boolean = false) {
        if (fromLiveCamera) { lastLiveQrRaw = raw; lastLiveQrAt = SystemClock.elapsedRealtime() }
        runOnUiThread {
            if (raw == lastQrRaw) return@runOnUiThread
            lastQrRaw = raw
            when (val result = parseQr(raw)) {
                is QrResult.Success -> { qrData = result.data; renderResult(); saveReceipt() }
                QrResult.NotAReceipt -> qrView.text = "Найден QR, но это не фискальный чек."
                is QrResult.MissingField -> qrView.text = "В QR отсутствует поле ${result.name}."
                is QrResult.InvalidField -> qrView.text = "Некорректное поле ${result.name} в QR."
            }
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val recentLiveQr = lastLiveQrRaw?.takeIf { qrData != null && SystemClock.elapsedRealtime() - lastLiveQrAt < 5_000L }
        resetReceipt()
        recentLiveQr?.let { onQrFound(it) }
        val file = File.createTempFile("receipt-", ".jpg", cacheDir)
        cameraButton.isEnabled = false
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), cameraWorker, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                runOnUiThread { cameraButton.isEnabled = true; analyzePhoto(Uri.fromFile(file), file) }
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                runOnUiThread { cameraButton.isEnabled = true; statusView.text = "Ошибка фото: ${exception.localizedMessage}" }
            }
        })
    }

    private fun analyzePhoto(uri: Uri, temporaryFile: File? = null) {
        val image = try { InputImage.fromFilePath(this, uri) } catch (error: Exception) {
            statusView.text = "Не удалось открыть изображение: ${error.localizedMessage}"; temporaryFile?.delete(); return
        }
        val generation = ++photoGeneration
        photoPending = true
        parsedText = null; rawLines = emptyList(); measurements.clear()
        comparisonView.text = "Сравнение выполняется…"; statusView.text = "Распознавание фото…"
        val remaining = AtomicInteger(3)
        fun finished() {
            if (remaining.decrementAndGet() == 0) {
                temporaryFile?.delete()
                if (generation == photoGeneration) photoPending = false
            }
        }
        scanner.process(image).addOnCompleteListener { initial ->
            val found = if (initial.isSuccessful) initial.result.firstOrNull { it.rawValue != null }?.rawValue else null
            if (found != null) {
                if (generation == photoGeneration) onQrFound(found)
                finished()
            } else {
                try {
                    val original = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                        ?: throw IllegalStateException("Не удалось открыть фото для повторного поиска QR")
                    val scaled = if (original.width < 800) Bitmap.createScaledBitmap(original, original.width * 3, original.height * 3, true) else original
                    scanner.process(InputImage.fromBitmap(scaled, 0)).addOnCompleteListener { retry ->
                        val retryFound = if (retry.isSuccessful) retry.result.firstOrNull { it.rawValue != null }?.rawValue else null
                        if (generation == photoGeneration && retryFound != null) onQrFound(retryFound)
                        if (scaled !== original) scaled.recycle()
                        original.recycle()
                        finished()
                    }
                } catch (error: Exception) {
                    if (generation == photoGeneration && !initial.isSuccessful) qrView.text = "Ошибка QR на фото: ${error.localizedMessage}"
                    finished()
                }
            }
        }

        val mlStart = SystemClock.elapsedRealtime()
        mlKit.process(image).addOnSuccessListener { result ->
            if (generation == photoGeneration) {
                val lines = result.textBlocks.flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
                    .map { it.text }
                onMeasurement(OcrMeasurement("ML Kit", lines, SystemClock.elapsedRealtime() - mlStart))
            }
        }.addOnFailureListener { if (generation == photoGeneration) onMeasurement(OcrMeasurement("ML Kit", emptyList(), SystemClock.elapsedRealtime() - mlStart, it.localizedMessage ?: "Ошибка OCR")) }
            .addOnCompleteListener { finished() }

        ocrWorker.execute {
            val start = SystemClock.elapsedRealtime()
            val measurement = try {
                val reader = tesseract ?: TesseractReader(applicationContext).also { tesseract = it }
                OcrMeasurement("Tesseract", reader.recognize(uri), SystemClock.elapsedRealtime() - start)
            }
                catch (error: Exception) { OcrMeasurement("Tesseract", emptyList(), SystemClock.elapsedRealtime() - start, error.localizedMessage ?: "Ошибка OCR") }
            runOnUiThread { if (generation == photoGeneration) onMeasurement(measurement); finished() }
        }
    }

    private fun onMeasurement(measurement: OcrMeasurement) {
        measurements[measurement.engine] = measurement
        if (measurement.engine == selectedEngine) selectMeasurement(measurement)
        comparisonView.text = listOf("Tesseract", "ML Kit").joinToString("\n") { name ->
            measurements[name]?.let { result ->
                "$name: ${result.elapsedMs} мс; итог ${result.parsed?.totalKopecks?.let(::money) ?: "—"}; позиций ${result.parsed?.items?.size ?: 0}${result.error?.let { "; ошибка: $it" } ?: ""}"
            } ?: "$name: выполняется…"
        }
    }

    private fun selectMeasurement(measurement: OcrMeasurement) {
        if (measurement.error != null) { statusView.text = "Ошибка ${measurement.engine}: ${measurement.error}"; return }
        parsedText = measurement.parsed
        rawLines = measurement.lines
        rawView.text = if (rawLines.isEmpty()) "Текст не распознан." else rawLines.joinToString("\n")
        renderResult()
        saveReceipt()
    }

    private fun saveReceipt() {
        val qr = qrData ?: return
        try {
            val store = ReceiptStore(this)
            val inserted = try { store.save(qr, parsedText, rawLines, if (parsedText == null) null else selectedEngine) } finally { store.close() }
            saveView.text = if (inserted) "Чек сохранён офлайн." else "Повторный чек обновлён без дубля."
        } catch (error: Exception) { saveView.text = "Не удалось сохранить чек: ${error.localizedMessage}" }
    }

    private fun renderResult() {
        val qr = qrData
        qrView.text = if (qr == null) "QR чека пока не распознан." else buildString {
            append("Дата: ${qr.dateTime.format(dateFormat)}\nСумма: ${money(qr.sumKopecks)} ₽\n")
            append("Тип: ${operationLabel(qr.operationType)}\nФН: ${qr.fn}; ФД: ${qr.documentNumber}; ФП: ${qr.fiscalSign}")
        }
        val parsed = parsedText
        receiptView.text = if (parsed == null) "Фото пока не распознано." else buildString {
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
            ReconcileStatus.OK -> if (parsed.totalKopecks == null)
                "Сумма позиций совпадает с QR; строка итога OCR не найдена."
            else "Всё сходится: итог OCR, позиции и QR совпадают."
            ReconcileStatus.UNVERIFIED -> "Позиции сходятся с итогом, но нет QR: итог не подтверждён."
            ReconcileStatus.TOTAL_MISMATCH -> "Не сходится итог OCR с суммой из QR."
            ReconcileStatus.ITEMS_MISMATCH -> "Не сходится сумма позиций: возможно, позиции потеряны."
            ReconcileStatus.NO_TOTAL -> "Итог не найден: сверка невозможна."
        }
        statusView.setTextColor(if (status == ReconcileStatus.OK && parsed.totalKopecks != null) Color.rgb(20, 120, 65) else Color.rgb(175, 65, 25))
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
        scanner.close(); mlKit.close(); cameraWorker.shutdown()
        ocrWorker.execute { tesseract?.close(); tesseract = null }
        ocrWorker.shutdown()
    }
}
