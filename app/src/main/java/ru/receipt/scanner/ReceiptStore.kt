package ru.receipt.scanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import ru.receipt.core.ParsedText
import ru.receipt.core.QrData
import ru.receipt.core.ReconcileStatus
import ru.receipt.core.reconcile

data class SavedReceipt(
    val date: String,
    val sumKopecks: Long,
    val documentNumber: String,
    val operation: String,
    val status: String,
    val engine: String?,
    val items: String,
)

class ReceiptStore(context: Context) : SQLiteOpenHelper(context, "receipts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            fn TEXT NOT NULL, document_no TEXT NOT NULL, fiscal_sign TEXT NOT NULL,
            qr_date TEXT NOT NULL, qr_sum INTEGER NOT NULL, operation TEXT NOT NULL,
            ocr_date TEXT, ocr_total INTEGER, items TEXT, raw_text TEXT, engine TEXT, status TEXT,
            saved_at INTEGER NOT NULL,
            PRIMARY KEY (fn, document_no, fiscal_sign)
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Неизвестная версия локальной базы")
    }

    /** Returns true only for a new fiscal document; rescans update the same row. */
    fun save(qr: QrData, text: ParsedText?, rawLines: List<String>, engine: String?): Boolean {
        val keyArgs = arrayOf(qr.fn, qr.documentNumber, qr.fiscalSign)
        val values = ContentValues().apply {
            put("fn", qr.fn); put("document_no", qr.documentNumber); put("fiscal_sign", qr.fiscalSign)
            put("qr_date", qr.dateTime.toString()); put("qr_sum", qr.sumKopecks); put("operation", qr.operationType.name)
            put("saved_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val inserted = db.insertWithOnConflict("receipts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (text != null) {
                val items = JSONArray()
                text.items.forEach { item ->
                    items.put(JSONObject().apply {
                        put("name", item.name); put("quantity", item.quantity.toPlainString())
                        put("priceKopecks", item.priceKopecks); put("amountKopecks", item.amountKopecks)
                    })
                }
                val update = ContentValues().apply {
                    put("ocr_date", text.dateTime?.toString()); put("ocr_total", text.totalKopecks)
                    put("items", items.toString()); put("raw_text", rawLines.joinToString("\n")); put("engine", engine)
                    put("status", reconcile(qr, text).name)
                }
                db.update("receipts", update, "fn=? AND document_no=? AND fiscal_sign=?", keyArgs)
            }
            db.setTransactionSuccessful()
            inserted
        } finally { db.endTransaction() }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM receipts", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    fun list(): List<SavedReceipt> {
        val output = mutableListOf<SavedReceipt>()
        readableDatabase.rawQuery("SELECT qr_date, qr_sum, document_no, operation, status, engine, items FROM receipts ORDER BY saved_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val items = cursor.getString(6)?.let { raw ->
                    val array = JSONArray(raw)
                    (0 until array.length()).joinToString("\n") { index ->
                        val item = array.getJSONObject(index)
                        "${item.getString("name")}: ${item.getString("quantity")} × ${item.getLong("priceKopecks")} коп. = ${item.getLong("amountKopecks")} коп."
                    }
                }.orEmpty()
                output += SavedReceipt(cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4) ?: ReconcileStatus.NO_TOTAL.name, cursor.getString(5), items)
            }
        }
        return output
    }
}
