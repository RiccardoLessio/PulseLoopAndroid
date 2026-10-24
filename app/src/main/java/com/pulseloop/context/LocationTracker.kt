package com.pulseloop.context

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.LocationPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Automatic location tracking, battery-sane:
 *
 * - Play Services present → FusedLocationProvider (the same stack GpsRouteRecorder uses for
 *   workouts) at `PRIORITY_BALANCED_POWER_ACCURACY` (Wi-Fi/cell, ~100 m, no GPS unless another app
 *   wants it); otherwise the platform LocationManager's network provider.
 * - The interval follows Activity Recognition ([TrackingProfile]): every 2 min while moving,
 *   every 15 min while still (one fix per quarter hour is enough for ring-hub's stay detection,
 *   which needs ≥ 10 min within 100 m), every 5 min when the activity is unknown.
 * - Fixes worse than [MAX_ACCURACY_M] are dropped; nothing is sent anywhere but ring-hub.
 */
class LocationTracker(private val context: Context) {

    data class TrackingProfile(val intervalMs: Long, val minDistanceM: Float, val maxDelayMs: Long)

    companion object {
        private const val TAG = "LocationTracker"
        const val MAX_ACCURACY_M = 300f
        private const val MIN_SPACING_MS = 20_000L
        private val MOVING = setOf("WALKING", "RUNNING", "ON_BICYCLE", "IN_VEHICLE")

        fun profileFor(activity: String?): TrackingProfile = when (activity) {
            in MOVING -> TrackingProfile(2 * 60_000L, 25f, 4 * 60_000L)
            "STILL" -> TrackingProfile(15 * 60_000L, 0f, 30 * 60_000L)
            else -> TrackingProfile(5 * 60_000L, 0f, 10 * 60_000L)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = PulseLoopDatabase.getInstance(context).locationPointDao()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val manager by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var running = false
    private var usingFused = false
    private var profile: TrackingProfile? = null
    private var lastSavedAt = 0L

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = result.locations.forEach { save(it, "fused") }
    }
    private val platformListener = LocationListener { save(it, it.provider ?: "network") }

    val isRunning: Boolean get() = running

    fun start(activity: String?) {
        if (!ContextPermissions.hasForegroundLocation(context)) return
        apply(profileFor(activity))
    }

    fun retune(activity: String?) {
        if (!running) return
        val next = profileFor(activity)
        if (next != profile) apply(next)
    }

    fun stop() {
        if (!running) return
        if (usingFused) fused.removeLocationUpdates(fusedCallback) else manager.removeUpdates(platformListener)
        running = false
        profile = null
    }

    @SuppressLint("MissingPermission")
    private fun apply(next: TrackingProfile) {
        if (running) stop()
        try {
            if (ContextPermissions.hasPlayServices(context)) {
                val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, next.intervalMs)
                    .setMinUpdateIntervalMillis(next.intervalMs / 2)
                    .setMaxUpdateDelayMillis(next.maxDelayMs)
                    .setMinUpdateDistanceMeters(next.minDistanceM)
                    .build()
                fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                usingFused = true
            } else {
                val provider = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else LocationManager.GPS_PROVIDER
                manager.requestLocationUpdates(provider, next.intervalMs, next.minDistanceM, platformListener, Looper.getMainLooper())
                usingFused = false
            }
            running = true
            profile = next
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing", e)
            running = false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no location provider", e)
            running = false
        }
    }

    private fun save(location: Location, provider: String) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        if (location.time - lastSavedAt < MIN_SPACING_MS) return
        lastSavedAt = location.time
        val row = LocationPointEntity(
            timestamp = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            speed = if (location.hasSpeed()) location.speed else null,
            provider = provider,
        )
        scope.launch { dao.insert(row) }
    }
}
