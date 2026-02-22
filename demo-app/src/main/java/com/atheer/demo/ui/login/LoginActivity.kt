package com.atheer.demo.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.atheer.demo.databinding.ActivityLoginBinding
import com.atheer.demo.ui.dashboard.DashboardActivity

/**
 * LoginActivity — شاشة تسجيل الدخول
 *
 * بعد التحقق من بيانات التاجر يُفتح DashboardActivity حيث
 * يختار المستخدم المسار (عميل أو نقطة مبيعات).
 *
 * بيانات تجريبية افتراضية:
 *   رقم التاجر : MERCHANT_001
 *   كلمة المرور: 1234
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val merchantId = binding.etMerchantId.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString()?.trim() ?: ""

        // إخفاء رسالة الخطأ السابقة
        binding.tvError.visibility = View.GONE
        binding.tilMerchantId.error = null
        binding.tilPassword.error = null

        // التحقق من عدم الفراغ
        if (merchantId.isEmpty() || password.isEmpty()) {
            binding.tvError.text = getString(com.atheer.demo.R.string.login_error_empty)
            binding.tvError.visibility = View.VISIBLE
            return
        }

        // التحقق من صحة البيانات التجريبية
        if (!isValidCredentials(merchantId, password)) {
            binding.tvError.text = getString(com.atheer.demo.R.string.login_error_invalid)
            binding.tvError.visibility = View.VISIBLE
            return
        }

        // الانتقال إلى لوحة التحكم مع تمرير رقم التاجر
        val intent = Intent(this, DashboardActivity::class.java).apply {
            putExtra(DashboardActivity.EXTRA_MERCHANT_ID, merchantId)
        }
        startActivity(intent)
        finish()
    }

    /**
     * في التطبيق الحقيقي يُستبدل هذا بطلب شبكة عبر Atheer SDK.
     * هنا نستخدم بيانات ثابتة للعرض التجريبي.
     */
    private fun isValidCredentials(merchantId: String, password: String): Boolean =
        merchantId == DEMO_MERCHANT_ID && password == DEMO_PASSWORD

    companion object {
        private const val DEMO_MERCHANT_ID = "MERCHANT_001"
        private const val DEMO_PASSWORD = "1234"
    }
}
