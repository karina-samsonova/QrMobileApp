package ru.receipt.scanner

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("scanner", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "Движок OCR"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Выбранный движок определяет основной результат. При обработке фото оба движка измеряются для сравнения."; textSize = 16f })
        val group = RadioGroup(this)
        val tesseract = RadioButton(this).apply { id = 1; text = "Tesseract (русский + английский)"; textSize = 17f }
        val mlKit = RadioButton(this).apply { id = 2; text = "ML Kit (латиница и цифры)"; textSize = 17f }
        group.addView(tesseract); group.addView(mlKit)
        group.check(if (prefs.getString("engine", "Tesseract") == "ML Kit") 2 else 1)
        group.setOnCheckedChangeListener { _, checked -> prefs.edit().putString("engine", if (checked == 2) "ML Kit" else "Tesseract").apply() }
        root.addView(group)
        setContentView(root)
    }
}
