package com.atheer.demo.ui.result

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.atheer.demo.R
import com.atheer.demo.databinding.ActivityTransactionResultBinding
import com.atheer.demo.ui.dashboard.DashboardActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TransactionResultActivity — شاشة نتيجة المعاملة
 *
 * تعرض تفاصيل المعاملة بعد اكتمالها (نجاح أو فشل)، وتوفر
 * خيارات العودة إلى الشاشة السابقة أو لوحة التحكم الرئيسية.
 */
class TransactionResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransactionResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        displayResult()
        setupButtons()
    }

    private fun displayResult() {
        val isSuccess = intent.getBooleanExtra(EXTRA_IS_SUCCESS, true)
        val transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID) ?: "—"
        val amount = intent.getLongExtra(EXTRA_AMOUNT, 0L)
        val currency = intent.getStringExtra(EXTRA_CURRENCY) ?: "SAR"
        val merchantId = intent.getStringExtra(EXTRA_MERCHANT_ID) ?: "—"
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        val isSynced = intent.getBooleanExtra(EXTRA_IS_SYNCED, false)

        if (isSuccess) {
            binding.ivResultIcon.setImageResource(R.drawable.ic_check_circle)
            binding.iconContainer.setBackgroundResource(R.drawable.bg_success_circle)
            binding.tvResultTitle.text = getString(R.string.result_success_title)
            binding.tvResultTitle.setTextColor(getColor(R.color.success_color))
        } else {
            binding.ivResultIcon.setImageResource(R.drawable.ic_error_circle)
            binding.iconContainer.setBackgroundResource(R.drawable.bg_error_circle)
            binding.tvResultTitle.text = getString(R.string.result_failed_title)
            binding.tvResultTitle.setTextColor(getColor(R.color.error_color))
        }

        binding.tvTransactionId.text = transactionId
        // تحويل المبلغ من هللة إلى ريال
        val amountRiyal = amount / 100.0
        binding.tvAmount.text = "%.2f ريال".format(amountRiyal)
        binding.tvCurrency.text = if (currency == "SAR") getString(R.string.sar_currency) else currency
        binding.tvMerchant.text = merchantId

        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        binding.tvTimestamp.text = dateFormat.format(Date(timestamp))

        binding.tvSyncStatus.text = if (isSynced) {
            getString(R.string.result_status_synced)
        } else {
            getString(R.string.result_status_offline)
        }
        binding.tvSyncStatus.setTextColor(
            getColor(if (isSynced) R.color.success_color else R.color.warning_color)
        )
    }

    private fun setupButtons() {
        // الانتقال إلى معاملة جديدة (العودة للشاشة السابقة)
        binding.btnNewTransaction.setOnClickListener {
            finish()
        }

        // العودة إلى لوحة التحكم الرئيسية
        binding.btnBackDashboard.setOnClickListener {
            val savedMerchantId = intent.getStringExtra(EXTRA_MERCHANT_ID) ?: "MERCHANT_001"
            val dashboardIntent = Intent(this, DashboardActivity::class.java).apply {
                putExtra(DashboardActivity.EXTRA_MERCHANT_ID, savedMerchantId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(dashboardIntent)
            finish()
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_CURRENCY = "extra_currency"
        const val EXTRA_MERCHANT_ID = "extra_merchant_id"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        const val EXTRA_IS_SUCCESS = "extra_is_success"
        const val EXTRA_IS_SYNCED = "extra_is_synced"
    }
}
