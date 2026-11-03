package ru.receipt.scanner

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HistoryActivity : Activity() {
    private fun statusLabel(value: String) = when (value) {
        "OK" -> "суммы совпадают"
        "UNVERIFIED" -> "без QR"
        "TOTAL_MISMATCH" -> "расхождение итога"
        "ITEMS_MISMATCH" -> "расхождение позиций"
        else -> "недостаточно данных"
    }

    private fun operationLabel(value: String) = when (value) {
        "INCOME" -> "приход"
        "INCOME_RETURN" -> "возврат прихода"
        "EXPENSE" -> "расход"
        "EXPENSE_RETURN" -> "возврат расхода"
        else -> value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ReceiptStore(this)
        val receipts = try { store.list() } finally { store.close() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "История чеков (${receipts.size})"; textSize = 24f; setTextColor(Color.rgb(25, 45, 65)) })
        if (receipts.isEmpty()) root.addView(TextView(this).apply { text = "Чеки с QR пока не сохранены."; textSize = 16f })
        receipts.forEach { receipt ->
            val amount = "${receipt.sumKopecks / 100}.${(receipt.sumKopecks % 100).toString().padStart(2, '0')}"
            root.addView(TextView(this).apply {
                text = "${receipt.date.replace('T', ' ')} · $amount ₽ · ${operationLabel(receipt.operation)}\nФД ${receipt.documentNumber} · ${statusLabel(receipt.status)}\nДвижок: ${receipt.engine ?: "—"}\n${receipt.items.ifBlank { "Позиции пока не распознаны" }}\n"
                textSize = 16f; setPadding(0, 16, 0, 16)
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
