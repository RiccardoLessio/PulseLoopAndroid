package com.pulseloop.context

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityTransitionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Activity Recognition Transition API (Play Services). The system batches transitions and wakes
 * [ActivityTransitionReceiver]; registering costs no polling on our side. Re-registered at app
 * start and after boot (registrations do not survive a reboot).
 */
object ActivityRecognitionManager {
    private const val TAG = "ActivityRecognition"
    private const val REQUEST_CODE = 4401

    val TYPES = mapOf(
        DetectedActivity.STILL to "STILL",
        DetectedActivity.WALKING to "WALKING",
        DetectedActivity.RUNNING to "RUNNING",
        DetectedActivity.ON_BICYCLE to "ON_BICYCLE",
        DetectedActivity.IN_VEHICLE to "IN_VEHICLE",
    )

    fun transitionName(type: Int) = if (type == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ActivityTransitionReceiver::class.java), flags,
        )
    }

    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val app = context.applicationContext
        val wanted = ContextPrefs.get(app).settings.value.activityEnabled &&
            ContextPermissions.hasActivityRecognition(app) && ContextPermissions.hasPlayServices(app)
        val client = ActivityRecognition.getClient(app)
        try {
            if (wanted) {
                val transitions = TYPES.keys.flatMap { type ->
                    listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                        ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
                    }
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(app))
                    .addOnFailureListener { Log.w(TAG, "register failed", it) }
            } else {
                client.removeActivityTransitionUpdates(pendingIntent(app))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "activity recognition permission missing", e)
        }
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtimeNanos()
        val rows = result.transitionEvents.mapNotNull { e ->
            val name = ActivityRecognitionManager.TYPES[e.activityType] ?: return@mapNotNull null
            ActivityTransitionEntity(
                timestamp = now - (elapsedNow - e.elapsedRealTimeNanos) / 1_000_000L,
                activityType = name,
                transition = ActivityRecognitionManager.transitionName(e.transitionType),
            )
        }
        if (rows.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                val dao = PulseLoopDatabase.getInstance(app).activityTransitionDao()
                rows.forEach { dao.insert(it) }
                rows.lastOrNull { it.transition == "ENTER" }?.let { MonitorService.onActivity(it.activityType) }
            } finally {
                pending.finish()
            }
        }
    }
}
