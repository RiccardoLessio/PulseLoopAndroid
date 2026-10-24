package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase

/**
 * A persistence subscriber for a process that has no [com.pulseloop.AppServices] yet — the
 * background ring sync after MagicOS killed the app. Without it the worker's history pass went
 * out on the bus with nobody listening. [com.pulseloop.AppServices] stops it on creation so the
 * two never persist the same event twice.
 */
object BackgroundPersistence {
    @Volatile private var subscriber: EventPersistenceSubscriber? = null

    @Synchronized
    fun get(context: Context): EventPersistenceSubscriber =
        subscriber ?: EventPersistenceSubscriber(
            context.applicationContext,
            PulseLoopDatabase.getInstance(context.applicationContext),
        ).also { subscriber = it }

    @Synchronized
    fun stop() {
        subscriber?.stop()
        subscriber = null
    }
}
