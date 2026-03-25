package com.atheer.sdk.nfc

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * ## AtheerFeedbackUtils
 * فئة مساعدة لتوفير تغذية راجعة (Feedback) للمستخدم عند نجاح عملية Tap عبر NFC.
 * تتضمن اهتزازاً (Haptic) وصوتاً (Audio) لضمان تجربة مستخدم مشابهة للمعايير العالمية.
 */
object AtheerFeedbackUtils {

    fun playSuccessFeedback(context: Context) {
        // 1. الاهتزاز (Haptic Feedback)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }

        // 2. الصوت (Audio Feedback)
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (e: Exception) {
            // تجاهل أخطاء الصوت في حالة كتم الجهاز
        }
    }
}