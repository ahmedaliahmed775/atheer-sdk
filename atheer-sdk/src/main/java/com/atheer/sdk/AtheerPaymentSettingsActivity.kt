package com.atheer.sdk

import android.app.Activity
import android.content.Intent
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.ComponentName
import com.atheer.sdk.hce.AtheerApduService

/**
 * نشاط إعدادات دفع Atheer
 * يسمح للمستخدم بتعيين هذا التطبيق كخيار دفع افتراضي في نظام أندرويد
 */
class AtheerPaymentSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إنشاء واجهة بسيطة برمجياً (لتجنب الاعتماد على ملفات XML إضافية)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "إعدادات دفع Atheer"
            textSize = 24f
            setPadding(0, 0, 0, 40)
        }

        val description = TextView(this).apply {
            text = "لإتمام عمليات الدفع عبر NFC بنجاح، يجب ضبط Atheer كتطبيق دفع افتراضي."
            textSize = 16f
            setPadding(0, 0, 0, 60)
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        }

        val btnSetDefault = Button(this).apply {
            text = "ضبط كخيار افتراضي"
            setOnClickListener {
                requestSetDefaultPaymentApp()
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(btnSetDefault)

        setContentView(layout)
    }

    /**
     * طلب من النظام ضبط هذا التطبيق كخيار افتراضي (Payment Default)
     */
    private fun requestSetDefaultPaymentApp() {
        val componentName = ComponentName(this, AtheerApduService::class.java)
        val cardEmulation = CardEmulation.getInstance(android.nfc.NfcAdapter.getDefaultAdapter(this))
        
        if (!cardEmulation.isDefaultServiceForCategory(componentName, CardEmulation.CATEGORY_PAYMENT)) {
            val intent = Intent(CardEmulation.ACTION_CHANGE_DEFAULT).apply {
                putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT)
                putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, componentName)
            }
            startActivityForResult(intent, 101)
        } else {
            Toast.makeText(this, "Atheer هو التطبيق الافتراضي بالفعل", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "تم الضبط بنجاح!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "فشل في ضبط التطبيق كافتراضي", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
