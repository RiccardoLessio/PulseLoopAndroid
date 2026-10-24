package com.pulseloop.diary

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pulseloop.ui.DeepLinks
import com.pulseloop.ui.screens.DiaryQuickScreen
import com.pulseloop.ui.theme.PulseLoopTheme
import java.time.LocalDate

/**
 * The one-screen diary that the evening full-screen intent opens over the lock screen (and the
 * morning 2-tap check). Its own task, so finishing it returns to whatever was on screen.
 */
class DiaryQuickActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MORNING = "morning"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val date = intent.getStringExtra(DiaryReminders.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: DiaryLogic.diaryDateFor(System.currentTimeMillis())
        val morning = intent.getBooleanExtra(EXTRA_MORNING, false)
        setContent {
            PulseLoopTheme {
                DiaryQuickScreen(
                    date = date,
                    morning = morning,
                    onDone = {
                        DiaryReminders.cancelNotification(this, morning)
                        finish()
                    },
                    onOpenFullDiary = { openFull(date) },
                )
            }
        }
    }

    /** singleInstance: a second tap (morning after evening) arrives here; show what it asks for. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun openFull(date: LocalDate) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val launch = {
            startActivity(DeepLinks.intent(this, "diary?date=$date"))
            DiaryReminders.cancelNotification(this, false)
            finish()
        }
        if (km.isKeyguardLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launch()
            })
        } else launch()
    }
}
