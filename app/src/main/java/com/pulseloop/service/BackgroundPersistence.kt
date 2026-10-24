package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase

/**
 * A persistence subscriber for a process that has no [com.pulseloop.AppServices] yet — the
 * background ring sync after MagicOS killed the app. Without it the worker's history pass went
 * out on the bus with nobody listening. [com.pulseloop.AppServices] takes over on creation
 * ([handOffTo]) so the two never persist the same event twice.
 */
object BackgroundPersistence {
    @Volatile private var subscriber: EventPersistenceSubscriber? = null

    /** Set once the app's own subscriber owns persistence; [get] then returns it. */
    @Volatile private var owner: EventPersistenceSubscriber? = null

    @Synchronized
    fun get(context: Context): EventPersistenceSubscriber =
        owner ?: subscriber ?: EventPersistenceSubscriber(
            context.applicationContext,
            PulseLoopDatabase.getInstance(context.applicationContext),
        ).also { subscriber = it }

    /**
     * Hands persistence to the app's subscriber with no window in which nobody listens: the bus
     * has `replay = 0`, so an event emitted between "background stopped" and "app started" (the
     * app used to start its subscriber only later, from the UI composition) was lost for good.
     * [EventPersistenceSubscriber.start] subscribes synchronously and is idempotent, so the
     * later UI `start()` is a no-op.
     */
    @Synchronized
    fun handOffTo(app: EventPersistenceSubscriber) {
        subscriber?.stop()
        subscriber = null
        app.start()
        owner = app
    }
}
