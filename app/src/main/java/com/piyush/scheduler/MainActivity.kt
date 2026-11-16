package com.piyush.scheduler

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var tabTimeline: ScrollView
    private lateinit var tabDashboard: ScrollView
    private lateinit var tabCalendar: ScrollView
    private lateinit var tabBrain: ScrollView

    private lateinit var btnNavTimeline: Button
    private lateinit var btnNavDashboard: Button
    private lateinit var btnNavCalendar: Button
    private lateinit var btnNavBrain: Button

    private var selectedCalendarDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private var isRescanMode: Boolean = false
    private var nextTargetTime: LocalTime? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateLiveCountdown()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bitmap = result.data?.extras?.get("data") as? Bitmap
        if (bitmap != null) {
            Toast.makeText(this, "Scanning timetable offline...", Toast.LENGTH_SHORT).show()
            TimetableEngine.scanTimetableBitmap(
                bitmap,
                onSuccess = { extractedList ->
                    show7DayVerificationDialog(extractedList.toMutableList())
                },
                onError = { err ->
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    show7DayVerificationDialog(mutableListOf())
                }
            )
        } else {
            show7DayVerificationDialog(
                mutableListOf(
                    TemplateEvent(0, 1, 1, "Agronomy Class", "08:00", "10:00"),
                    TemplateEvent(0, 1, 1, "Agronomy Lab", "10:00", "12:00")
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindNavigation()
        bindTimelineTab()
        bindDashboardTab()
        bindCalendarTab()
        bindBrainTab()

        CoroutineScope(Dispatchers.IO).launch {
            AutomationEngine.scheduleMidnightScan(this@MainActivity)
            AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
            withContext(Dispatchers.Main) {
                refreshAllScreens()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        timerHandler.post(countdownRunnable)
        refreshAllScreens()
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(countdownRunnable)
    }

    private fun bindNavigation() {
        tabTimeline = findViewById(R.id.tabTimeline)
        tabDashboard = findViewById(R.id.tabDashboard)
        tabCalendar = findViewById(R.id.tabCalendar)
        tabBrain = findViewById(R.id.tabBrain)

        btnNavTimeline = findViewById(R.id.btnNavTimeline)
        btnNavDashboard = findViewById(R.id.btnNavDashboard)
        btnNavCalendar = findViewById(R.id.btnNavCalendar)
        btnNavBrain = findViewById(R.id.btnNavBrain)

        btnNavTimeline.setOnClickListener { switchTab(1) }
        btnNavDashboard.setOnClickListener { switchTab(2) }
        btnNavCalendar.setOnClickListener { switchTab(3) }
        btnNavBrain.setOnClickListener { switchTab(4) }

        switchTab(2)
    }

    private fun switchTab(index: Int) {
        tabTimeline.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabDashboard.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabCalendar.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabBrain.visibility = if (index == 4) View.VISIBLE else View.GONE

        val activeColor = Color.parseColor("#64B5F6")
        val inactiveColor = Color.parseColor("#90A4AE")
        btnNavTimeline.setTextColor(if (index == 1) activeColor else inactiveColor)
        btnNavDashboard.setTextColor(if (index == 2) activeColor else inactiveColor)
        btnNavCalendar.setTextColor(if (index == 3) activeColor else inactiveColor)
        btnNavBrain.setTextColor(if (index == 4) activeColor else inactiveColor)

        refreshAllScreens()
    }

    private fun bindTimelineTab() {
        val btnScanNew = findViewById<Button>(R.id.btnScanNewSemester)
        val btnRescan = findViewById<Button>(R.id.btnRescanOverwrite)
        val btnCopyShare = findViewById<Button>(R.id.btnCopyShareCode)
        val btnImport = findViewById<Button>(R.id.btnImportCode)
        val etImport = findViewById<EditText>(R.id.etImportCode)
        val tvShareDisplay = findViewById<TextView>(R.id.tvShareCodeDisplay)

        btnScanNew.setOnClickListener {
            isRescanMode = false
            launchCameraForOcr()
        }

        btnRescan.setOnClickListener {
            isRescanMode = true
            launchCameraForOcr()
        }

        btnCopyShare.setOnClickListener {
            val code = tvShareDisplay.text.toString()
            if (code.startsWith("RPCAU-")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Timetable Share Code", code))
                Toast.makeText(this, "Share Code copied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Scan a timetable first to generate a code.", Toast.LENGTH_SHORT).show()
            }
        }

        btnImport.setOnClickListener {
            val rawCode = etImport.text.toString().trim()
            if (rawCode.isEmpty()) return@setOnClickListener
            CoroutineScope(Dispatchers.IO).launch {
                val success = TimetableEngine.importShareCode(this@MainActivity, rawCode)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Semester Imported Successfully!", Toast.LENGTH_LONG).show()
                        switchTab(2)
                    } else {
                        Toast.makeText(this@MainActivity, "Invalid Share Code format.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun launchCameraForOcr() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            show7DayVerificationDialog(mutableListOf())
        }
    }

    private fun show7DayVerificationDialog(templates: MutableList<TemplateEvent>) {
        val dayNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }

        fun renderGridList() {
            listContainer.removeAllViews()
            templates.sortWith(compareBy({ it.dayOfWeek }, { it.startTime }))
            templates.forEachIndexed { idx, item ->
                val dayLabel = dayNames.getOrElse(item.dayOfWeek - 1) { "Mon" }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 12, 8, 12)
                }
                val tvInfo = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "[$dayLabel] ${item.startTime} - ${item.endTime}\n${item.title}"
                    setTextColor(Color.WHITE)
                }
                val btnDel = Button(this).apply {
                    text = "Delete"
                    setOnClickListener {
                        templates.removeAt(idx)
                        renderGridList()
                    }
                }
                row.addView(tvInfo)
                row.addView(btnDel)
                listContainer.addView(row)
            }
        }

        renderGridList()

        val btnAddBlock = Button(this).apply {
            text = "+ Add Class to 7-Day Grid"
            setOnClickListener {
                showAddTemplateBlockDialog { newBlock ->
                    templates.add(newBlock)
                    renderGridList()
                }
            }
        }

        rootLayout.addView(btnAddBlock)
        rootLayout.addView(scroll)

        AlertDialog.Builder(this)
            .setTitle("Verify 7-Day Timetable Grid")
            .setView(rootLayout)
            .setPositiveButton("CONFIRM & GENERATE") { _, _ ->
                val semName = findViewById<EditText>(R.id.etSemesterName).text.toString().ifBlank { "Fall 2026" }
                val endStr = findViewById<EditText>(R.id.etSemesterEndDate).text.toString().ifBlank { "2026-12-20" }
                val startStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                CoroutineScope(Dispatchers.IO).launch {
                    TimetableEngine.buildAndSaveSemester(
                        this@MainActivity, semName, startStr, endStr, templates, isRescanMode
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Semester Schedule Generated!", Toast.LENGTH_LONG).show()
                        switchTab(2)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTemplateBlockDialog(onAdded: (TemplateEvent) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }
        val etDay = EditText(this).apply { hint = "Day Number (1=Mon ... 7=Sun)"; setText("1") }
        val etTitle = EditText(this).apply { hint = "Subject (e.g., Agronomy Class)" }
        val etStart = EditText(this).apply { hint = "Start 24h (e.g., 08:00)"; setText("08:00") }
        val etEnd = EditText(this).apply { hint = "End 24h (e.g., 10:00)"; setText("10:00") }
        layout.addView(etDay); layout.addView(etTitle); layout.addView(etStart); layout.addView(etEnd)

        AlertDialog.Builder(this)
            .setTitle("Add Weekly Class Block")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val day = (etDay.text.toString().toIntOrNull() ?: 1).coerceIn(1, 7)
                val title = etTitle.text.toString().ifBlank { "Class" }
                val start = etStart.text.toString().ifBlank { "08:00" }
                val end = etEnd.text.toString().ifBlank { "10:00" }
                onAdded(TemplateEvent(0, 1, day, title, start, end))
            }
            .show()
    }

    private fun bindDashboardTab() {
        findViewById<Button>(R.id.btnEmptyGoToTimeline).setOnClickListener {
            switchTab(1)
        }
        findViewById<Button>(R.id.btnQuickAddToday).setOnClickListener {
            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            showSingleEventDialog(todayStr, null)
        }
    }

    private fun updateLiveCountdown() {
        val tvTimer = findViewById<TextView>(R.id.tvCountdownTimer)
        val target = nextTargetTime
        if (target == null) {
            tvTimer.text = "--:--:--"
            return
        }
        val now = LocalTime.now()
        if (now.isBefore(target)) {
            val diffSec = Duration.between(now, target).seconds
            val hrs = diffSec / 3600
            val mins = (diffSec % 3600) / 60
            val secs = diffSec % 60
            tvTimer.text = String.format("%02d:%02d:%02d", hrs, mins, secs)
        } else {
            refreshAllScreens()
        }
    }

    private fun bindCalendarTab() {
        val calView = findViewById<CalendarView>(R.id.calendarView)
        calView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedCalendarDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
            loadCalendarDrillDown(selectedCalendarDate)
        }

        findViewById<Button>(R.id.btnMarkHoliday).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.getDatabase(this@MainActivity).scheduleDao().deleteAllEventsOnDate(selectedCalendarDate)
                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Marked $selectedCalendarDate as Holiday", Toast.LENGTH_SHORT).show()
                    loadCalendarDrillDown(selectedCalendarDate)
                }
            }
        }

        findViewById<Button>(R.id.btnRestoreHoliday).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                TimetableEngine.restoreHolidayFromTemplate(this@MainActivity, selectedCalendarDate)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restored Schedule for $selectedCalendarDate", Toast.LENGTH_SHORT).show()
                    loadCalendarDrillDown(selectedCalendarDate)
                }
            }
        }

        findViewById<Button>(R.id.btnAddEventOnDate).setOnClickListener {
            showSingleEventDialog(selectedCalendarDate, null)
        }
    }

    private fun loadCalendarDrillDown(dateStr: String) {
        findViewById<TextView>(R.id.tvSelectedDateTitle).text = "Schedule for $dateStr"
        val container = findViewById<LinearLayout>(R.id.containerDateEvents)

        CoroutineScope(Dispatchers.IO).launch {
            val events = AppDatabase.getDatabase(this@MainActivity).scheduleDao().getEventsForDate(dateStr)
            withContext(Dispatchers.Main) {
                container.removeAllViews()
                if (events.isEmpty()) {
                    val tvEmpty = TextView(this@MainActivity).apply {
                        text = "Holiday / No classes scheduled on this date."
                        setTextColor(Color.parseColor("#EF9A9A"))
                        setPadding(8, 12, 8, 12)
                    }
                    container.addView(tvEmpty)
                    return@withContext
                }

                events.forEach { ev ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(12, 16, 12, 16)
                        setBackgroundColor(Color.parseColor("#1E2028"))
                    }
                    val tv = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "${ev.startTime} - ${ev.endTime}\n${ev.title}"
                        setTextColor(Color.WHITE)
                    }
                    val btnEdit = Button(this@MainActivity).apply {
                        text = "Edit"
                        setOnClickListener { showSingleEventDialog(dateStr, ev) }
                    }
                    val btnDel = Button(this@MainActivity).apply {
                        text = "Delete"
                        setOnClickListener {
                            CoroutineScope(Dispatchers.IO).launch {
                                AppDatabase.getDatabase(this@MainActivity).scheduleDao().deleteSingleEvent(ev)
                                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                                withContext(Dispatchers.Main) { loadCalendarDrillDown(dateStr) }
                            }
                        }
                    }
                    row.addView(tv); row.addView(btnEdit); row.addView(btnDel)
                    container.addView(row)
                }
            }
        }
    }

    private fun showSingleEventDialog(targetDate: String, existing: ScheduleEvent?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }
        val etTitle = EditText(this).apply {
            hint = "Event Title (e.g., Special Seminar)"
            setText(existing?.title ?: "")
        }
        val etStart = EditText(this).apply {
            hint = "Start Time 24h (e.g., 14:00)"
            setText(existing?.startTime ?: "14:00")
        }
        val etEnd = EditText(this).apply {
            hint = "End Time 24h (e.g., 16:00)"
            setText(existing?.endTime ?: "16:00")
        }
        layout.addView(etTitle); layout.addView(etStart); layout.addView(etEnd)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Event ($targetDate)" else "Edit Event")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().ifBlank { "Special Class" }
                val start = etStart.text.toString().ifBlank { "14:00" }
                val end = etEnd.text.toString().ifBlank { "16:00" }

                CoroutineScope(Dispatchers.IO).launch {
                    val dao = AppDatabase.getDatabase(this@MainActivity).scheduleDao()
                    if (existing == null) {
                        dao.insertSingleEvent(ScheduleEvent(0, 1, targetDate, title, start, end, true))
                    } else {
                        dao.updateEvent(existing.copy(title = title, startTime = start, endTime = end))
                    }
                    AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        refreshAllScreens()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindBrainTab() {
        val prefs = getSharedPreferences("smart_brain_prefs", Context.MODE_PRIVATE)
        val swAuto = findViewById<Switch>(R.id.switchAutoMidnightScan)
        val swOverride = findViewById<Switch>(R.id.switchOverrideSafeZone)

        swAuto.isChecked = prefs.getBoolean("auto_scan_enabled", true)
        swOverride.isChecked = prefs.getBoolean("override_safe_zone", false)

        swAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_scan_enabled", isChecked).apply()
        }

        swOverride.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("override_safe_zone", isChecked).apply()
            CoroutineScope(Dispatchers.IO).launch {
                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
            }
        }

        findViewById<Button>(R.id.btnForceSafeZoneNow).setOnClickListener {
            val currentSsid = AutomationEngine.getCurrentWifiSsid(this)
            if (currentSsid != null) {
                prefs.edit().putString("learned_safe_zone", currentSsid).apply()
                refreshAllScreens()
                Toast.makeText(this, "Locked $currentSsid as Hostel Safe Zone!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Connect to Wi-Fi & enable Location first.", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnGrantDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantBattery).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
        }
    }

    private fun refreshAllScreens() {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val prefs = getSharedPreferences("smart_brain_prefs", Context.MODE_PRIVATE)

        val learnedWifi = prefs.getString("learned_safe_zone", "Not Learned Yet (Waiting for 3:00 AM Scan)")
        findViewById<TextView>(R.id.tvSafeZoneStatus).text = "Current Safe Zone: $learnedWifi"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val dndOk = nm.isNotificationPolicyAccessGranted
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.tvHealthStatus).text =
            "- DND Access: ${if (dndOk) "Granted" else "Missing"}\n" +
            "- Battery Bypass: ${if (battOk) "Active" else "Restricted"}\n" +
            "- Wi-Fi & Camera: ${if (locOk) "Granted" else "Missing"}"

        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(this@MainActivity).scheduleDao()
            val activeSem = dao.getActiveSemester(todayStr)
            val todayEvents = dao.getEventsForDate(todayStr)

            withContext(Dispatchers.Main) {
                val tvSemInfo = findViewById<TextView>(R.id.tvActiveSemesterInfo)
                val tvShare = findViewById<TextView>(R.id.tvShareCodeDisplay)
                val cardEmpty = findViewById<LinearLayout>(R.id.cardEmptyState)

                if (activeSem != null) {
                    tvSemInfo.text = "Active: ${activeSem.semesterName}\n(${activeSem.startDate} to ${activeSem.endDate})"
                    tvShare.text = activeSem.shareCode
                    cardEmpty.visibility = View.GONE
                } else {
                    tvSemInfo.text = "No Active Semester Found"
                    cardEmpty.visibility = View.VISIBLE
                }

                findViewById<TextView>(R.id.tvCurrentModeStatus).text =
                    prefs.getString("current_mode", "Normal Mode (Ringing)")
                findViewById<TextView>(R.id.tvTodayDateHeader).text = "Today's Schedule ($todayStr)"

                val nextEv = todayEvents.firstOrNull { it.isEnabled && it.startTime > nowStr }
                if (nextEv != null) {
                    nextTargetTime = LocalTime.parse(nextEv.startTime, DateTimeFormatter.ofPattern("HH:mm"))
                    findViewById<TextView>(R.id.tvNextEventSubtitle).text =
                        "Starts at ${nextEv.startTime}: ${nextEv.title}"
                } else {
                    nextTargetTime = null
                    findViewById<TextView>(R.id.tvNextEventSubtitle).text =
                        "No more classes starting today"
                }

                val containerToday = findViewById<LinearLayout>(R.id.containerTodayEvents)
                containerToday.removeAllViews()

                todayEvents.forEach { ev ->
                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(24, 24, 24, 24)
                        setBackgroundColor(Color.parseColor("#1E2028"))
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val tvDetails = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "${ev.startTime} - ${ev.endTime}\n${ev.title}" +
                                if (!ev.isEnabled) " (Canceled)" else ""
                        setTextColor(if (ev.isEnabled) Color.WHITE else Color.GRAY)
                        if (!ev.isEnabled) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    }
                    val swToggle = Switch(this@MainActivity).apply {
                        isChecked = ev.isEnabled
                        setOnCheckedChangeListener { _, checked ->
                            CoroutineScope(Dispatchers.IO).launch {
                                dao.toggleEventStatus(ev.eventId, checked)
                                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                                withContext(Dispatchers.Main) { refreshAllScreens() }
                            }
                        }
                    }
                    card.addView(tvDetails)
                    card.addView(swToggle)
                    containerToday.addView(card)
                }

                loadCalendarDrillDown(selectedCalendarDate)
            }
        }
    }
}
