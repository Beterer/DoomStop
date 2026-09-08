package dev.personal.doomstop

import android.app.Application
import dev.personal.doomstop.admin.PolicyController
import dev.personal.doomstop.core.DeadlineScheduler
import dev.personal.doomstop.core.LimiterCoordinator
import dev.personal.doomstop.data.BootMarkerStore
import dev.personal.doomstop.data.LimiterDao
import dev.personal.doomstop.data.LimiterDatabase
import dev.personal.doomstop.monitor.AndroidClockSource
import dev.personal.doomstop.monitor.MonitorNotifications
import dev.personal.doomstop.monitor.UsageEventReader
import dev.personal.doomstop.security.DaoPinStore
import dev.personal.doomstop.security.PinManager

/**
 * Application object and the whole of the app's dependency wiring.
 *
 * No DI framework, by design (plan section 4): one module, one database, one coordinator.
 * Everything is lazy so that a direct-boot receiver can start this process without touching
 * credential-protected storage before the first unlock -- constructing the database here
 * eagerly would crash exactly when early-boot enforcement is needed most.
 */
class DoomStopApp : Application() {

    val database: LimiterDatabase by lazy { LimiterDatabase.build(this) }
    val dao: LimiterDao by lazy { database.dao() }

    val policy: PolicyController by lazy { PolicyController(this) }
    val usageReader: UsageEventReader by lazy { UsageEventReader(this) }
    val bootMarker: BootMarkerStore by lazy { BootMarkerStore(this) }
    val notifications: MonitorNotifications by lazy { MonitorNotifications(this) }

    /** One clock for everything, so PIN cooldowns and accounting agree about reboots. */
    val clock: AndroidClockSource by lazy { AndroidClockSource(this) }

    val pinManager: PinManager by lazy { PinManager(DaoPinStore(dao), clock) }

    val coordinator: LimiterCoordinator by lazy {
        LimiterCoordinator(
            context = this,
            dao = dao,
            policy = policy,
            reader = usageReader,
            bootMarker = bootMarker,
            clock = clock,
            deadlines = DeadlineScheduler(this),
        )
    }
}
