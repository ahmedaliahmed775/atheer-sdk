package com.atheer.demo.ui.customer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atheer.demo.R
import com.atheer.demo.databinding.ActivityCustomerBinding
import com.atheer.demo.ui.result.TransactionResultActivity
import com.atheer.sdk.AtheerSdk
import com.atheer.sdk.model.AtheerTransaction
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * CustomerActivity — مسار العميل (HCE)
 *
 * يتيح للمستخدم:
 * 1. إدخال مبلغ الدفع ورقم البطاقة
 * 2. تجهيز بيانات الدفع عبر Atheer SDK (Tokenization + Encryption)
 * 3. تحويل الهاتف إلى بطاقة NFC لاتلامسية جاهزة للدفع
 */
class CustomerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomerBinding
    private var merchantId: String = "MERCHANT_001"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        merchantId = intent.getStringExtra(EXTRA_MERCHANT_ID) ?: "MERCHANT_001"

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPreparePayment.setOnClickListener { preparePayment() }
    }

    private fun preparePayment() {
        val amountText = binding.etAmount.text?.toString()?.trim() ?: ""
        val cardNumber = binding.etCardNumber.text?.toString()?.trim() ?: ""

        // التحقق من صحة المبلغ
        val amount = amountText.toLongOrNull()
        if (amount == null || amount <= 0) {
            binding.tilAmount.error = getString(R.string.error_invalid_amount)
            return
        }
        binding.tilAmount.error = null

        // التحقق من صحة رقم البطاقة (16 رقمًا)
        if (cardNumber.length != 16 || !cardNumber.all { it.isDigit() }) {
            binding.tilCardNumber.error = getString(R.string.error_invalid_card)
            return
        }
        binding.tilCardNumber.error = null

        // عرض حالة التحضير
        binding.btnPreparePayment.isEnabled = false
        binding.tvNfcStatus.text = getString(R.string.payment_preparing)

        lifecycleScope.launch {
            try {
                val sdk = AtheerSdk.getInstance()
                val keystoreManager = sdk.getKeystoreManager()

                // ترميز رقم البطاقة
                val tokenizedCard = keystoreManager.tokenize(cardNumber)

                // بناء كائن المعاملة
                val transaction = AtheerTransaction(
                    transactionId = "TXN_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
                    amount = amount,
                    currency = "SAR",
                    merchantId = merchantId,
                    tokenizedCard = tokenizedCard
                )

                // معالجة المعاملة عبر SDK
                sdk.processTransaction(
                    transaction = transaction,
                    onSuccess = { _ ->
                        showPaymentReady()
                        // الانتقال لعرض النتيجة بعد ثانية
                        binding.root.postDelayed({
                            openResultScreen(transaction, true)
                        }, 1500)
                    },
                    onError = { error ->
                        binding.tvNfcStatus.text = "خطأ: ${error.message}"
                        binding.btnPreparePayment.isEnabled = true
                    }
                )
            } catch (e: Exception) {
                binding.tvNfcStatus.text = getString(R.string.sdk_init_error)
                binding.btnPreparePayment.isEnabled = true
            }
        }
    }

    private fun showPaymentReady() {
        binding.tvNfcStatus.text = getString(R.string.hce_status_ready)
        binding.tvNfcGuide.visibility = View.VISIBLE
        binding.tvNfcGuide.text = getString(R.string.payment_ready)

        // تأثير وميض لأيقونة NFC
        val blink = AlphaAnimation(0.3f, 1.0f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.ivNfcIcon.startAnimation(blink)
        binding.ivNfcIcon.alpha = 1f
    }

    private fun openResultScreen(transaction: AtheerTransaction, synced: Boolean) {
        val intent = Intent(this, TransactionResultActivity::class.java).apply {
            putExtra(TransactionResultActivity.EXTRA_TRANSACTION_ID, transaction.transactionId)
            putExtra(TransactionResultActivity.EXTRA_AMOUNT, transaction.amount)
            putExtra(TransactionResultActivity.EXTRA_CURRENCY, transaction.currency)
            putExtra(TransactionResultActivity.EXTRA_MERCHANT_ID, transaction.merchantId)
            putExtra(TransactionResultActivity.EXTRA_TIMESTAMP, transaction.timestamp)
            putExtra(TransactionResultActivity.EXTRA_IS_SUCCESS, true)
            putExtra(TransactionResultActivity.EXTRA_IS_SYNCED, synced)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.ivNfcIcon.clearAnimation()
    }

    companion object {
        const val EXTRA_MERCHANT_ID = "extra_merchant_id"
    }
}
