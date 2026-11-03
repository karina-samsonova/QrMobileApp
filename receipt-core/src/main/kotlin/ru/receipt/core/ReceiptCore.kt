package ru.receipt.core

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDateTime

enum class OperationType { INCOME, INCOME_RETURN, EXPENSE, EXPENSE_RETURN }

data class QrData(
    val dateTime: LocalDateTime,
    val sumKopecks: Long,
    val fn: String,
    val documentNumber: String,
    val fiscalSign: String,
    val operationType: OperationType,
)

sealed interface QrResult {
    data class Success(val data: QrData) : QrResult
    data object NotAReceipt : QrResult
    data class MissingField(val name: String) : QrResult
    data class InvalidField(val name: String) : QrResult
}

data class Item(
    val name: String,
    val quantity: BigDecimal,
    val priceKopecks: Long,
    val amountKopecks: Long,
)

data class ParsedText(
    val totalKopecks: Long?,
    val dateTime: LocalDateTime?,
    val items: List<Item>,
)

enum class ReconcileStatus { OK, UNVERIFIED, TOTAL_MISMATCH, ITEMS_MISMATCH, NO_TOTAL }

private val requiredQrKeys = listOf("t", "s", "fn", "i", "fp", "n")
private val qrDatePattern = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})?$")
private val moneyPattern = Regex("^\\d+(?:[.,]\\d{1,2})?$")
private val amountToken = "(\\d[\\d ]*(?:[.,]\\d{1,2})?)"
private val quantityToken = "(\\d[\\d ]*(?:[.,]\\d+)?)"
private val itemPattern = Regex("^\\s*$quantityToken\\s*[xXхХ*]\\s*$amountToken\\s*=\\s*$amountToken\\s*$")
private val totalPattern = Regex("^\\s*(?:ИТОГО?|К\\s+ОПЛАТЕ)\\s*[:=]?\\s*$amountToken\\s*$", RegexOption.IGNORE_CASE)
private val datePattern = Regex("(?<!\\d)(\\d{2})\\.(\\d{2})\\.(\\d{2}|\\d{4})\\s+(\\d{2}):(\\d{2})(?!\\d)")
private val servicePattern = Regex("^(?:КАССОВЫЙ ЧЕК|ПРИХОД|ВОЗВРАТ ПРИХОДА|(?:ООО|ИП|АО|ПАО|НАЛИЧНЫМИ|БЕЗНАЛИЧНЫМИ|ПОЛУЧЕНО|СДАЧА)(?:\\s|[:=]|$).*)$", RegexOption.IGNORE_CASE)

/** Monetary values are always parsed and stored as integer kopecks. */
private fun kopecks(value: String): Long? {
    val compact = value.replace(" ", "").replace(',', '.')
    if (!moneyPattern.matches(compact)) return null
    return try { BigDecimal(compact).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { null }
}

fun parseQr(raw: String): QrResult {
    val pairs = raw.split('&').mapNotNull { part ->
        val equal = part.indexOf('=')
        if (equal <= 0) null else part.substring(0, equal) to part.substring(equal + 1)
    }
    if (pairs.isEmpty() || pairs.none { it.first in requiredQrKeys }) return QrResult.NotAReceipt
    val fields = pairs.toMap()
    for (key in requiredQrKeys) if (key !in fields) return QrResult.MissingField(key)
    val dateMatch = qrDatePattern.matchEntire(fields.getValue("t")) ?: return QrResult.InvalidField("t")
    val date = try {
        val g = dateMatch.groupValues
        LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
    } catch (_: DateTimeException) { return QrResult.InvalidField("t") }
    val sum = kopecks(fields.getValue("s")) ?: return QrResult.InvalidField("s")
    for (key in listOf("fn", "i", "fp")) if (fields.getValue(key).isBlank()) return QrResult.InvalidField(key)
    val operation = when (fields.getValue("n")) {
        "1" -> OperationType.INCOME
        "2" -> OperationType.INCOME_RETURN
        "3" -> OperationType.EXPENSE
        "4" -> OperationType.EXPENSE_RETURN
        else -> return QrResult.InvalidField("n")
    }
    return QrResult.Success(QrData(date, sum, fields.getValue("fn"), fields.getValue("i"), fields.getValue("fp"), operation))
}

private fun receiptDate(lines: List<String>): LocalDateTime? {
    for (line in lines) {
        for (match in datePattern.findAll(line)) {
            val g = match.groupValues
            val year = if (g[3].length == 2) 2000 + g[3].toInt() else g[3].toInt()
            try { return LocalDateTime.of(year, g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt()) }
            catch (_: DateTimeException) { /* keep searching */ }
        }
    }
    return null
}

private fun isService(line: String): Boolean {
    val clean = line.trim()
    return clean.isEmpty() || servicePattern.matches(clean) || totalPattern.matches(clean) ||
        datePattern.containsMatchIn(clean) || itemPattern.matches(clean)
}

fun parseReceiptText(lines: List<String>): ParsedText {
    var total: Long? = null
    val items = mutableListOf<Item>()
    for ((index, line) in lines.withIndex()) {
        if (total == null) total = totalPattern.matchEntire(line)?.groupValues?.get(1)?.let(::kopecks)
        val match = itemPattern.matchEntire(line) ?: continue
        val quantity = try { BigDecimal(match.groupValues[1].replace(" ", "").replace(',', '.')) }
            catch (_: NumberFormatException) { continue }
        val price = kopecks(match.groupValues[2]) ?: continue
        val amount = kopecks(match.groupValues[3]) ?: continue
        val names = mutableListOf<String>()
        for (previous in (index - 1) downTo maxOf(0, index - 2)) {
            if (isService(lines[previous])) break
            names.add(0, lines[previous].trim())
        }
        if (names.isNotEmpty()) items += Item(names.joinToString(" "), quantity, price, amount)
    }
    return ParsedText(total, receiptDate(lines), items)
}

fun reconcile(qr: QrData?, text: ParsedText): ReconcileStatus {
    if (qr != null && text.totalKopecks != null && qr.sumKopecks != text.totalKopecks) return ReconcileStatus.TOTAL_MISMATCH
    val reference = qr?.sumKopecks ?: text.totalKopecks ?: return ReconcileStatus.NO_TOTAL
    val itemSum = try { text.items.fold(0L) { sum, item -> Math.addExact(sum, item.amountKopecks) } }
        catch (_: ArithmeticException) { return ReconcileStatus.ITEMS_MISMATCH }
    if (itemSum != reference) return ReconcileStatus.ITEMS_MISMATCH
    return if (qr == null) ReconcileStatus.UNVERIFIED else ReconcileStatus.OK
}

/** In-memory deduplication contract; persistent storage is a separate app concern. */
class ReceiptHistory {
    private val keys = mutableSetOf<Triple<String, String, String>>()
    fun save(qr: QrData, text: ParsedText? = null): Boolean = keys.add(Triple(qr.fn, qr.documentNumber, qr.fiscalSign))
    fun count(): Int = keys.size
}
