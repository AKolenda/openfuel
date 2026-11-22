// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * What a person chose for nearby price alerts. [maxPrice] is in tenths of a cent per litre, like station prices.
 * [recentOnly] skips prices reported more than a day ago.
 */
data class AlertSettings(
    val enabled: Boolean = false,
    val grade: Grade = Grade.REGULAR,
    val maxPrice: Int = 1500,
    val radiusMetres: Int = 5000,
    val intervalMinutes: Int = 15,
    val recentOnly: Boolean = true
) {
    companion object {
        val RADII = listOf(1000, 2000, 5000, 10000)
        val INTERVALS = listOf(15, 30, 60)
        const val RECENT_MINUTES = 24 * 60
    }
}

/** What a price-alert check found, for the settings screen. */
sealed interface AlertCheck {
    data object NoLocation : AlertCheck
    data object MissingPermission : AlertCheck
    data class Found(val station: Station, val more: Int, val notified: Boolean) : AlertCheck
    data object NoneFound : AlertCheck
    data class Failed(val message: String) : AlertCheck
}

/** The permission a price alert still needs, in the order they are asked for. */
enum class AlertPermission { NOTIFICATIONS, LOCATION, BACKGROUND_LOCATION }

/**
 * Nearby price alerts. When they are on, a periodic job takes a one-shot location fix, asks the API for the
 * stations around it (rounded to three decimals, as every search is) and notifies about the cheapest one within
 * the chosen distance whose price is at or under the chosen limit. Neither the fix nor the stations are saved;
 * only which stations were already announced, so the same price is not announced again within a day.
 */
object PriceAlerts {
    private const val PREFS = "openfuel-alerts"
    private const val JOB_ID = 4201
    private const val CHANNEL = "price-alerts"
    private const val NOTIFICATION_ID = 4201
    private const val REPEAT_MS = 24 * 60 * 60_000L
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun settings(context: Context): AlertSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = AlertSettings(grade = Grade.entries.find {
            it.name == context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).getString("fuel-grade", null)
        } ?: Grade.REGULAR)
        return AlertSettings(
            enabled = prefs.getBoolean("enabled", defaults.enabled),
            grade = Grade.entries.find { it.name == prefs.getString("grade", null) } ?: defaults.grade,
            maxPrice = prefs.getInt("max-price", defaults.maxPrice).takeIf { it in 500..3999 } ?: defaults.maxPrice,
            radiusMetres = prefs.getInt("radius", defaults.radiusMetres).takeIf { it in AlertSettings.RADII } ?: defaults.radiusMetres,
            intervalMinutes = prefs.getInt("interval", defaults.intervalMinutes).takeIf { it in AlertSettings.INTERVALS } ?: defaults.intervalMinutes,
            recentOnly = prefs.getBoolean("recent-only", defaults.recentOnly))
    }

    /** Saves [settings] and starts, reschedules or stops the periodic check to match. */
    fun save(context: Context, settings: AlertSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", settings.enabled).putString("grade", settings.grade.name)
            .putInt("max-price", settings.maxPrice).putInt("radius", settings.radiusMetres)
            .putInt("interval", settings.intervalMinutes).putBoolean("recent-only", settings.recentOnly).apply()
        schedule(context, settings)
    }

    /** Schedules the check again if alerts are on, off the UI thread. Android cancels it when the app is force-stopped. */
    fun resume(context: Context) {
        val appContext = context.applicationContext
        background.launch { runCatching { schedule(appContext, settings(appContext)) } }
    }

    private fun schedule(context: Context, settings: AlertSettings) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!settings.enabled) { scheduler.cancel(JOB_ID); return }
        val interval = settings.intervalMinutes * 60_000L
        // Rescheduling restarts the period, so an unchanged job is left to run when it was due.
        if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == interval) return
        runCatching {
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, PriceAlertJob::class.java))
                .setPeriodic(interval)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        }
    }

    /** The permissions alerts still need, in the order they are requested. */
    fun missing(context: Context): List<AlertPermission> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) add(AlertPermission.NOTIFICATIONS)
        if (!hasLocationPermission(context)) add(AlertPermission.LOCATION)
        if (Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add(AlertPermission.BACKGROUND_LOCATION)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks for a station worth an alert now. [manual] checks run while the settings screen is open: they use a
     * fresh foreground fix and announce a match even if it was announced before.
     */
    suspend fun check(context: Context, manual: Boolean): AlertCheck = withContext(Dispatchers.IO) {
        val settings = settings(context)
        if (!manual && (!settings.enabled || missing(context).isNotEmpty())) return@withContext AlertCheck.MissingPermission
        if (!hasLocationPermission(context)) return@withContext AlertCheck.MissingPermission
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // After the daily limit, background checks wait until it resets.
        if (!manual && now < prefs.getLong("paused-until", 0)) return@withContext AlertCheck.Failed("Paused until the daily limit resets.")
        val point = (if (manual) currentSearchPoint(context) else currentSearchPoint(context, maxAgeMs = 5 * 60_000L, timeoutMs = 30_000))
            ?: return@withContext AlertCheck.NoLocation
        val stations = try { StationRepository(context).prefetch(point).await() }
        catch (e: ServiceLimitException) {
            prefs.edit().putLong("paused-until", e.resetsAt.toEpochMilli()).apply()
            return@withContext AlertCheck.Failed(e.message ?: "OpenFuel's database reached its free daily limit.")
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { return@withContext AlertCheck.Failed(e.message ?: "Station service unavailable.") }
        catch (e: RuntimeException) { return@withContext AlertCheck.Failed("Unexpected station response.") }
        val matches = alertMatches(stations, settings)
        if (matches.isEmpty()) return@withContext AlertCheck.NoneFound
        val announced = announced(context, now)
        val fresh = if (manual) matches else matches.filter { s ->
            announced[key(s, settings.grade)]?.let { it > s.price(settings.grade)!! } ?: true
        }
        if (fresh.isEmpty()) return@withContext AlertCheck.Found(matches.first(), matches.size - 1, notified = false)
        val notified = notify(context, fresh.first(), fresh.size - 1, settings)
        if (notified) saveAnnounced(context, announced + fresh.associate { key(it, settings.grade) to it.price(settings.grade)!! }, now)
        AlertCheck.Found(fresh.first(), fresh.size - 1, notified)
    }

    /** Stations within the alert distance at or under its price, cheapest then nearest first. */
    fun alertMatches(stations: List<Station>, settings: AlertSettings): List<Station> = stations.filter { s ->
        val price = s.price(settings.grade)
        price != null && price <= settings.maxPrice && s.distanceMetres <= settings.radiusMetres &&
            (!settings.recentOnly || s.age(settings.grade) <= AlertSettings.RECENT_MINUTES)
    }.sortedWith(compareBy<Station> { it.price(settings.grade) }.thenBy { it.distanceMetres }.thenBy { it.id })

    private fun key(station: Station, grade: Grade) = "${station.id}:${grade.name}"

    /** The price each station and grade was last announced at, for those announced within the last day. */
    private fun announced(context: Context, now: Long): Map<String, Int> = runCatching {
        val json = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("announced", null) ?: return emptyMap())
        json.keys().asSequence().mapNotNull { k ->
            val entry = json.getJSONObject(k)
            if (now - entry.getLong("at") in 0 until REPEAT_MS) k to entry.getInt("price") else null
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveAnnounced(context: Context, prices: Map<String, Int>, now: Long) {
        val previous = runCatching { JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("announced", null) ?: "{}") }.getOrDefault(JSONObject())
        val json = JSONObject()
        prices.forEach { (k, price) ->
            val old = previous.optJSONObject(k)
            // A repeated price keeps the time it was first announced, so it may be announced again a day later.
            val at = if (old != null && old.optInt("price") == price && now - old.optLong("at") in 0 until REPEAT_MS) old.getLong("at") else now
            json.put(k, JSONObject().put("price", price).put("at", at))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("announced", json.toString()).apply()
    }

    private fun notify(context: Context, station: Station, more: Int, settings: AlertSettings): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.alerts_channel_description) })
        val price = station.price(settings.grade)!!
        val grade = context.getString(when (settings.grade) { Grade.REGULAR -> R.string.regular; Grade.PREMIUM -> R.string.premium; Grade.DIESEL -> R.string.diesel })
        val text = buildString {
            append(context.getString(R.string.alerts_notification_text, FuelCore.distanceText(station.distanceMetres), ageText(context, station.age(settings.grade))))
            if (more > 0) append('\n').append(context.resources.getQuantityString(R.plurals.alerts_notification_more, more, more, FuelCore.priceText(settings.maxPrice)))
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alerts_notification_title, station.name, FuelCore.priceText(price), grade))
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        if (FuelCore.validStationPoint(station.latitude, station.longitude)) {
            val directions = PendingIntent.getActivity(context, 1, Intent(Intent.ACTION_VIEW, Uri.parse(FuelCore.directionsUrl(station))),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, context.getString(R.string.alerts_directions), directions)
        }
        return try { manager.notify(NOTIFICATION_ID, builder.build()); true } catch (_: SecurityException) { false }
    }

    private fun ageText(context: Context, age: Int): String = when {
        age == Int.MAX_VALUE -> context.getString(R.string.alerts_no_time)
        age < 60 -> context.getString(R.string.reported_minutes, age)
        age < 1440 -> context.getString(R.string.reported_hours, age / 60)
        age % 1440 < 60 -> context.getString(R.string.reported_days, age / 1440)
        else -> context.getString(R.string.reported_days_hours, age / 1440, age % 1440 / 60)
    }

    internal fun runJob(service: JobService, params: JobParameters): Job = background.launch {
        try { check(service.applicationContext, manual = false) } finally { service.jobFinished(params, false) }
    }
}

/** Runs the periodic price-alert check that [PriceAlerts] schedules. */
class PriceAlertJob : JobService() {
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean { running = PriceAlerts.runJob(this, params); return true }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
}
