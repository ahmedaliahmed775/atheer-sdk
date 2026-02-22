package com.atheer.demo.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.atheer.demo.R
import com.atheer.demo.databinding.ActivityDashboardBinding
import com.atheer.demo.ui.customer.CustomerActivity
import com.atheer.demo.ui.login.LoginActivity
import com.atheer.demo.ui.pos.PosActivity

/**
 * DashboardActivity — لوحة التحكم الرئيسية
 *
 * تعرض مسارين للمستخدم بعد تسجيل الدخول:
 *  1. مسار العميل  → CustomerActivity  (HCE — محاكاة بطاقة NFC)
 *  2. نقطة المبيعات → PosActivity      (SoftPOS — قارئ NFC)
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val merchantId = intent.getStringExtra(EXTRA_MERCHANT_ID) ?: "MERCHANT_001"

        // عرض رقم التاجر في الشريط العلوي
        binding.tvMerchantId.text = getString(R.string.merchant_id_label, merchantId)

        // الانتقال إلى مسار العميل
        binding.cardCustomer.setOnClickListener {
            startActivity(
                Intent(this, CustomerActivity::class.java).apply {
                    putExtra(CustomerActivity.EXTRA_MERCHANT_ID, merchantId)
                }
            )
        }

        // الانتقال إلى مسار نقطة المبيعات
        binding.cardPos.setOnClickListener {
            startActivity(
                Intent(this, PosActivity::class.java).apply {
                    putExtra(PosActivity.EXTRA_MERCHANT_ID, merchantId)
                }
            )
        }

        // تسجيل الخروج
        binding.btnLogout.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }
    }

    companion object {
        const val EXTRA_MERCHANT_ID = "extra_merchant_id"
    }
}
