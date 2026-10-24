package com.pulseloop.ui

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification → screen routing. A notification's launch intent carries [EXTRA_ROUTE]; MainActivity
 * hands it here and PulseLoopApp navigates once its NavHost exists, then [consume]s it.
 */
object DeepLinks {
    const val EXTRA_ROUTE = "pulseloop.route"
    private val allowed = setOf("settings/adaptive", "labels", "diary", "settings/context", "settings/zones")

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun handle(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        if (route.substringBefore('?') in allowed) _pending.value = route
        intent.removeExtra(EXTRA_ROUTE)
    }

    fun consume() { _pending.value = null }

    /**
     * An explicit MainActivity intent: a launcher intent would only bring a running task forward
     * and drop the extras. MainActivity is singleTask, so a running one gets onNewIntent.
     */
    fun intent(context: Context, route: String): Intent =
        Intent(context, com.pulseloop.MainActivity::class.java)
            .setAction("com.pulseloop.OPEN_ROUTE")
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
