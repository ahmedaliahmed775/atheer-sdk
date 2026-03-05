package com.atheer.sdk

import android.content.Context
import android.util.Log
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.database.TransactionEntity
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * الواجهة الرئيسية (Facade) لـ Atheer SDK
 *
 * هذه الفئة هي نقطة الدخول الوحيدة للتطبيقات الخارجية للتفاعل مع المكتبة.
 * تطبق نمط Facade لإخفاء تعقيدات الطبقات الداخلية وتقديم واجهة بسيطة وموحدة.
 *
 * نمط التهيئة:
 * ```kotlin
 * // في Application.onCreate():
 * AtheerSdk.init(context, "MERCHANT_ID_123", "https://api.atheer.com")
 *
 * // استخدام SDK:
 * val sdk = AtheerSdk.getInstance()
 * sdk.processTransaction(transaction)
 * ```
 *
 * المعمارية المطبقة: Clean Architecture
 * - طبقة البيانات: AtheerDatabase, AtheerNetworkRouter
 * - طبقة المجال: AtheerKeystoreManager, AtheerApduService, AtheerNfcReader
 * - طبقة العرض: هذه الفئة (Facade)
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    private val apiBaseUrl: String
) {

    companion object {
        private const val TAG = "AtheerSdk"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة SDK بالإعدادات الأساسية
         *
         * يجب استدعاء هذه الدالة مرة واحدة فقط، ويُنصَح باستدعائها في
         * Application.onCreate() لضمان التهيئة قبل أي استخدام.
         *
         * @param context سياق التطبيق (يُنصَح باستخدام applicationContext)
         * @param merchantId معرف التاجر الصادر من Atheer
         * @param apiBaseUrl رابط الخادم الأساسي للـ API
         * @throws IllegalStateException إذا تم استدعاء init أكثر من مرة
         */
        fun init(context: Context, merchantId: String, apiBaseUrl: String) {
            Log.i(TAG, "جاري تهيئة Atheer SDK - معرف التاجر: $merchantId")
            if (instance != null) {
                Log.w(TAG, "تحذير: تم استدعاء init أكثر من مرة - سيتم تجاهل الاستدعاء الثاني")
                return
            }
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(
                        context = context.applicationContext,
                        merchantId = merchantId,
                        apiBaseUrl = apiBaseUrl
                    )
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح")
                }
            }
        }

        /**
         * الحصول على النسخة الوحيدة من SDK (نمط Singleton)
         *
         * @return النسخة الوحيدة من AtheerSdk
         * @throws IllegalStateException إذا لم يتم استدعاء init أولاً
         */
        fun getInstance(): AtheerSdk {
            return instance ?: throw IllegalStateException(
                "لم يتم تهيئة Atheer SDK بعد. يرجى استدعاء AtheerSdk.init() أولاً"
            )
        }

        /**
         * إعادة تعيين النسخة - للاستخدام في الاختبارات فقط
         * لا تستخدم هذه الدالة في بيئة الإنتاج
         */
        internal fun resetForTesting() {
            instance = null
            Log.d(TAG, "تم إعادة تعيين Atheer SDK للاختبارات")
        }
    }

    // مكونات SDK الداخلية
    private val keystoreManager: AtheerKeystoreManager = AtheerKeystoreManager()
    private val networkRouter: AtheerNetworkRouter = AtheerNetworkRouter(context)
    private val database: AtheerDatabase = AtheerDatabase.getInstance(context)

    // نطاق Coroutines الخاص بـ SDK
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * معالجة معاملة دفع كاملة
     *
     * الخطوات:
     * 1. توليد Nonce لمنع هجمات إعادة التشغيل
     * 2. ترميز بيانات البطاقة (Tokenization)
     * 3. حفظ المعاملة محلياً في قاعدة البيانات المشفرة (للوضع غير المتصل)
     * 4. محاولة إرسال المعاملة للخادم عبر الشبكة الخلوية
     * 5. تحديث حالة المزامنة عند النجاح
     *
     * @param transaction بيانات المعاملة المراد معالجتها
     * @param accessToken رمز المصادقة (Bearer Token) لإرساله في الـ Authorization Header
     * @param onSuccess دالة رد النداء عند نجاح المعالجة
     * @param onError دالة رد النداء عند الفشل
     */
    fun processTransaction(
        transaction: AtheerTransaction,
        accessToken: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.i(TAG, "جاري معالجة معاملة دفع جديدة - المعرف: ${transaction.transactionId}")
        sdkScope.launch {
            try {
                // الخطوة 1: توليد Nonce
                val nonce = keystoreManager.generateNonce()
                Log.d(TAG, "تم توليد Nonce: ${nonce.take(8)}...")

                // الخطوة 2: ترميز بيانات البطاقة إن وُجدت
                // يتطلب transaction.tokenizedCard أن يكون محضراً مسبقاً
                val tokenizedCard = transaction.tokenizedCard
                    ?: throw IllegalArgumentException(
                        "يجب توفير tokenizedCard في كائن AtheerTransaction قبل معالجة المعاملة"
                    )

                // الخطوة 3: تشفير القيمة وحفظ المعاملة محلياً
                val encryptedAmount = keystoreManager.encrypt(transaction.amount.toString())
                val transactionEntity = TransactionEntity(
                    transactionId = transaction.transactionId,
                    encryptedAmount = encryptedAmount,
                    currency = transaction.currency,
                    merchantId = merchantId,
                    timestamp = transaction.timestamp,
                    encryptedToken = tokenizedCard,
                    nonce = nonce,
                    isSynced = false
                )

                database.transactionDao().insertTransaction(transactionEntity)
                Log.i(TAG, "تم حفظ المعاملة محلياً - المعرف: ${transaction.transactionId}")

                // الخطوة 4: محاولة الإرسال عبر الشبكة الخلوية
                if (networkRouter.isNetworkAvailable()) {
                    try {
                        val requestBody = buildTransactionJson(
                            transaction.transactionId,
                            tokenizedCard,
                            nonce,
                            transaction.amount
                        )
                        val response = networkRouter.executeViaCellular(
                            "$apiBaseUrl/transactions",
                            requestBody,
                            accessToken
                        )

                        // الخطوة 5: تحديث حالة المزامنة عند النجاح
                        database.transactionDao().updateSyncStatus(
                            transaction.transactionId,
                            true
                        )
                        Log.i(TAG, "تمت معالجة المعاملة بنجاح عبر الشبكة: ${transaction.transactionId}")

                        withContext(Dispatchers.Main) {
                            onSuccess(response)
                        }
                    } catch (networkError: Exception) {
                        // في حالة فشل الشبكة، يظل السجل محلياً للمزامنة لاحقاً
                        Log.w(TAG, "فشل في إرسال المعاملة عبر الشبكة - ستتم المزامنة لاحقاً: ${networkError.message}")
                        withContext(Dispatchers.Main) {
                            onSuccess("تم حفظ المعاملة للمزامنة عند توفر الشبكة")
                        }
                    }
                } else {
                    // الوضع غير المتصل: المعاملة محفوظة محلياً
                    Log.i(TAG, "الجهاز غير متصل بالشبكة - تم حفظ المعاملة للمزامنة لاحقاً")
                    withContext(Dispatchers.Main) {
                        onSuccess("تم حفظ المعاملة بنجاح في الوضع غير المتصل")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في معالجة المعاملة: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    /**
     * مزامنة المعاملات غير المتزامنة مع الخادم
     *
     * تُستدعى هذه الدالة عند استعادة الاتصال بالشبكة لإرسال جميع
     * المعاملات التي تمت في الوضع غير المتصل.
     *
     * @param accessToken رمز المصادقة (Bearer Token) لإرساله في الـ Authorization Header
     * @param onComplete دالة رد النداء عند اكتمال المزامنة مع عدد المعاملات المُزامَنة
     */
    fun syncPendingTransactions(accessToken: String, onComplete: (Int) -> Unit) {
        Log.i(TAG, "جاري مزامنة المعاملات غير المتزامنة...")
        sdkScope.launch {
            try {
                val unsyncedTransactions = database.transactionDao().getUnsyncedTransactions()
                Log.d(TAG, "عدد المعاملات غير المتزامنة: ${unsyncedTransactions.size}")

                var syncedCount = 0
                for (entity in unsyncedTransactions) {
                    try {
                        // فك تشفير المبلغ للحصول على القيمة الأصلية
                        val decryptedAmount = try {
                            keystoreManager.decrypt(entity.encryptedAmount).toLongOrNull() ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                        val requestBody = buildTransactionJson(
                            entity.transactionId,
                            entity.encryptedToken ?: "",
                            entity.nonce ?: "",
                            decryptedAmount
                        )
                        networkRouter.executeViaCellular(
                            "$apiBaseUrl/transactions",
                            requestBody,
                            accessToken
                        )
                        database.transactionDao().updateSyncStatus(entity.transactionId, true)
                        syncedCount++
                        Log.d(TAG, "تمت مزامنة المعاملة: ${entity.transactionId}")
                    } catch (e: Exception) {
                        Log.w(TAG, "فشل في مزامنة المعاملة ${entity.transactionId}: ${e.message}")
                    }
                }

                Log.i(TAG, "اكتملت المزامنة - عدد المعاملات المُزامَنة: $syncedCount")
                withContext(Dispatchers.Main) {
                    onComplete(syncedCount)
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في عملية المزامنة: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete(0)
                }
            }
        }
    }

    /**
     * بناء جسم طلب JSON لإرسال بيانات المعاملة للخادم
     *
     * يتبع هيكل JSON المعياري للمحافظ المالية بكائنين رئيسيين:
     * - header: يحتوي على معرف الخدمة ومعرف المعاملة
     * - body: يحتوي على بيانات الدفع الأساسية
     *
     * @param transactionId معرف المعاملة
     * @param token الرمز المميز للبطاقة
     * @param nonce الرقم العشوائي لمنع هجمات إعادة التشغيل
     * @param amount قيمة المعاملة
     * @return جسم الطلب بصيغة JSON
     */
    private fun buildTransactionJson(
        transactionId: String,
        token: String,
        nonce: String,
        amount: Long
    ): String {
        // بناء JSON منظم باستخدام JSONObject لضمان سلامة البناء ومنع هجمات الحقن
        val header = JSONObject().apply {
            put("serviceName", "ATHEER.NFC.PAYMENT")
            put("transactionId", transactionId)
        }
        val body = JSONObject().apply {
            put("merchantId", merchantId)
            put("atheerToken", token)
            put("nonce", nonce)
            put("amount", amount)
        }
        return JSONObject().apply {
            put("header", header)
            put("body", body)
        }.toString()
    }

    /**
     * الحصول على مدير مخزن المفاتيح للاستخدام المباشر
     * للتشفير وفك التشفير وعمليات Tokenization
     *
     * @return كائن AtheerKeystoreManager
     */
    fun getKeystoreManager(): AtheerKeystoreManager = keystoreManager

    /**
     * الحصول على موجه الشبكة للاستخدام المباشر
     *
     * @return كائن AtheerNetworkRouter
     */
    fun getNetworkRouter(): AtheerNetworkRouter = networkRouter

    /**
     * الحصول على قاعدة البيانات للاستخدام المتقدم
     *
     * @return كائن AtheerDatabase
     */
    fun getDatabase(): AtheerDatabase = database
}
