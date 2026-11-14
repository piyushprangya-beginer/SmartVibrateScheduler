package com.piyush.scheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent?.action) {
                    AutomationEngine.ACTION_MIDNIGHT_SCAN -> {
                        AutomationEngine.performMidnightSafeZoneScan(context)
                        AutomationEngine.scheduleMidnightScan(context)
                    }
                    else -> {
                        AutomationEngine.evaluateAndScheduleNext(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutomationEngine.evaluateAndScheduleNext(context)
                AutomationEngine.scheduleMidnightScan(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

object AutomationEngine {
    const val ACTION_EVALUATE = "com.piyush.scheduler.ACTION_EVALUATE"
    const val ACTION_MIDNIGHT_SCAN = "com.piyush.scheduler.ACTION_MIDNIGHT_SCAN"
    private const val PREFS_NAME = "smart_brain_prefs"

    suspend fun evaluateAndScheduleNext(context: Context) {
        val db = AppDatabase.getDatabase(context).scheduleDao()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowTime = LocalTime.now()
        val nowStr = nowTime.format(DateTimeFormatter.ofPattern("HH:mm"))

        // 1. Purge expired semesters automatically
        db.purgeExpiredSemesters(todayStr)

        // 2. Fetch today's active schedule
        val todayEvents = db.getEventsForDate(todayStr).filter { it.isEnabled }

        // 3. Check if any class is currently active right now
        val activeEvent = todayEvents.firstOrNull { event ->
            nowStr >= event.startTime && nowStr < event.endTime
        }

        // 4. Check Hostel Safe Zone override
        val isInSafeZone = isConnectedToSafeZone(context)

        // 5. Apply Ringer State Machine
        setDeviceRinger(context, shouldVibrate = (activeEvent != null && !isInSafeZone))

        // 6. Find the next boundary time today (either current class end or next class start)
        val upcomingTimes = mutableListOf<String>()
        todayEvents.forEach { event ->
            if (event.startTime > nowStr) upcomingTimes.add(event.startTime)
            if (event.endTime > nowStr) upcomingTimes.add(event.endTime)
        }

        val nextBoundaryStr = upcomingTimes.minOrNull()
        if (nextBoundaryStr != null) {
            val nextTime = LocalTime.parse(nextBoundaryStr, DateTimeFormatter.ofPattern("HH:mm"))
            val triggerDateTime = LocalDateTime.of(LocalDate.now(), nextTime)
            scheduleExactAlarm(context, triggerDateTime, ACTION_EVALUATE, 1001)
        } else {
            // No more classes today; schedule check for 00:05 AM tomorrow
            val tomorrowStart = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(0, 5))
            scheduleExactAlarm(context, tomorrowStart, ACTION_EVALUATE, 1001)
        }
    }

    private fun setDeviceRinger(context: Context, shouldVibrate: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Prevent crash if user hasn't granted Do Not Disturb access yet
        if (!notificationManager.isNotificationPolicyAccessGranted) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (shouldVibrate) {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_VIBRATE) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
            prefs.edit().putString("current_mode", "Event Mode (Vibrate)").apply()
        } else {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            prefs.edit().putString("current_mode", "Normal Mode (Ringing)").apply()
        }
    }

    fun performMidnightSafeZoneScan(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_scan_enabled", true)) return

        val currentSsid = getCurrentWifiSsid(context) ?: return
        val candidateSsid = prefs.getString("candidate_ssid", "")
        var streak = prefs.getInt("candidate_streak", 0)

        if (currentSsid == candidateSsid) {
            streak++
        } else {
            streak = 1
            prefs.edit().putString("candidate_ssid", currentSsid).apply()
        }

        prefs.edit().putInt("candidate_streak", streak).apply()

        // Lock in as Safe Zone after 3 consecutive nights at 3:00 AM
        if (streak >= 3) {
            prefs.edit().putString("learned_safe_zone", currentSsid).apply()
        }
    }

    fun getCurrentWifiSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo ?: return null
        val rawSsid = info.ssid ?: return null
        val cleanSsid = rawSsid.removePrefix("\"").removeSuffix("\"")
        if (cleanSsid.isBlank() || cleanSsid.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return cleanSsid
    }

    fun isConnectedToSafeZone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("override_safe_zone", false)) return false
        val safeZone = prefs.getString("learned_safe_zone", null) ?: return false
        val currentSsid = getCurrentWifiSsid(context) ?: return false
        return safeZone == currentSsid
    }

    fun scheduleMidnightScan(context: Context) {
        var next3AM = LocalDateTime.of(LocalDate.now(), LocalTime.of(3, 0))
        if (LocalDateTime.now().isAfter(next3AM)) {
            next3AM = next3AM.plusDays(1)
        }
        scheduleExactAlarm(context, next3AM, ACTION_MIDNIGHT_SCAN, 2002)
    }

    private fun scheduleExactAlarm(
        context: Context,
        targetDateTime: LocalDateTime,
        actionName: String,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = actionName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }
}
