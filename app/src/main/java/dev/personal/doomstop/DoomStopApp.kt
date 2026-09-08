package dev.personal.doomstop

import android.app.Application
import dev.personal.doomstop.data.LimiterDatabase

/**
 * Application object and the whole of the app's dependency wiring.
 *
 * No DI framework by design (plan section 4): there is one module, one database and one
 * coordinator, and a lazily built holder is easier to reason about than a graph.
 */
class DoomStopApp : Application() {

    val database: LimiterDatabase by lazy { LimiterDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    companion object {
        @Volatile
        private var INSTANCE: DoomStopApp? = null

        fun get(): DoomStopApp = requireNotNull(INSTANCE) { "DoomStopApp has not been created yet" }
    }
}
