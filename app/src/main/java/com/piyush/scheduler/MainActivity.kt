package com.piyush.scheduler

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
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
    private var selectedSemesterEndDate: String = LocalDate.now().plusMonths(4).format(DateTimeFormatter.ISO_LOCAL_DATE)
    private var nextTargetTime: LocalTime? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateLiveCountdown()
            timerHandler.postDelayed(this, 1000)
        }
    }

    // 1. Gallery Image Picker for High-Resolution Timetable Screenshots
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                runOcrOnBitmap(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not load image from Gallery.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 2. Camera Photo Launcher
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bitmap = result.data?.extras?.get("data") as? Bitmap
        if (bitmap != null) {
            runOcrOnBitmap(bitmap)
        } else {
            Toast.makeText(this, "Camera scan canceled.", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Camera Permission Launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCameraIntent()
        } else {
            Toast.makeText(this, "Camera permission is required to take a timetable photo.", Toast.LENGTH_LONG).show()
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

    // ================= 1. BOTTOM NAVIGATION =================
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

    // ================= 2. TIMETABLE TAB, GALLERY/CAMERA OCR & 7-DAY GRID =================
    private fun bindTimelineTab() {
        val btnPickEndDate = findViewById<Button>(R.id.btnPickEndDate)
        val btnOpenGrid = findViewById<Button>(R.id.btnOpenWeeklyGrid)
        val btnPickGallery = findViewById<Button>(R.id.btnPickGalleryTimetable)
        val btnScanCamera = findViewById<Button>(R.id.btnScanNewSemester)
        val btnCopyShare = findViewById<Button>(R.id.btnCopyShareCode)
        val btnImport = findViewById<Button>(R.id.btnImportCode)
        val etImport = findViewById<EditText>(R.id.etImportCode)
        val tvShareDisplay = findViewById<TextView>(R.id.tvShareCodeDisplay)

        btnPickEndDate.text = "Semester End Date: $selectedSemesterEndDate (Tap to Pick)"
        btnPickEndDate.setOnClickListener {
            val parsed = runCatching {
                LocalDate.parse(selectedSemesterEndDate, DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrDefault(LocalDate.now().plusMonths(4))

            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    selectedSemesterEndDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    btnPickEndDate.text = "Semester End Date: $selectedSemesterEndDate (Tap to Pick)"
                },
                parsed.year,
                parsed.monthValue - 1,
                parsed.dayOfMonth
            ).show()
        }

        // Open or Edit 7-Day Weekly Grid directly without needing Camera
        btnOpenGrid.setOnClickListener {
            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            CoroutineScope(Dispatchers.IO).launch {
                val dao = AppDatabase.getDatabase(this@MainActivity).scheduleDao()
                val activeSem = dao.getActiveSemester(todayStr)
                val existingTemplates = if (activeSem != null) {
                    dao.getMasterTemplate(activeSem.semesterId).toMutableList()
                } else {
                    mutableListOf()
                }
                withContext(Dispatchers.Main) {
                    show7DayVerificationDialog(existingTemplates)
                }
            }
        }

        // Pick a clear screenshot from phone Gallery
        btnPickGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        // Check Camera permission first before opening Camera
        btnScanCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCameraIntent()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnCopyShare.setOnClickListener {
            val code = tvShareDisplay.text.toString()
            if (code.startsWith("RPCAU-")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Timetable Share Code", code))
                Toast.makeText(this, "Share Code copied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Save your 7-day timetable first to generate a Share Code.", Toast.LENGTH_SHORT).show()
            }
        }

        btnImport.setOnClickListener {
            val rawCode = etImport.text.toString().trim()
            if (rawCode.isEmpty()) {
                Toast.makeText(this, "Paste a classmate's RPCAU-... code first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                val success = TimetableEngine.importShareCode(this@MainActivity, rawCode)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Timetable Imported Successfully!", Toast.LENGTH_LONG).show()
                        switchTab(2)
                    } else {
                        Toast.makeText(this@MainActivity, "Invalid Share Code. Make sure it starts with RPCAU-.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun openCameraIntent() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open camera.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        Toast.makeText(this, "Reading timetable text offline...", Toast.LENGTH_SHORT).show()
        TimetableEngine.scanTimetableBitmap(
            bitmap,
            onSuccess = { extractedList ->
                if (extractedList.isEmpty()) {
                    Toast.makeText(this, "No clear class blocks detected. Add classes below.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Detected ${extractedList.size} class blocks! Verify below.", Toast.LENGTH_SHORT).show()
                }
                show7DayVerificationDialog(extractedList.toMutableList())
            },
            onError = { err ->
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                show7DayVerificationDialog(mutableListOf())
            }
        )
    }

    // Interactive 7-Day Weekly Grid Editor (Tap to Add, Edit, or Delete Classes)
    private fun show7DayVerificationDialog(templates: MutableList<TemplateEvent>) {
        val dayNames = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }

        fun renderGridList() {
            listContainer.removeAllViews()
            templates.sortWith(compareBy({ it.dayOfWeek }, { it.startTime }))

            if (templates.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = "No weekly classes added yet.\nTap '+ Add Weekly Class' above to add your classes."
                    setTextColor(Color.LTGRAY)
                    setPadding(8, 24, 8, 24)
                }
                listContainer.addView(tvEmpty)
                return
            }

            templates.forEachIndexed { idx, item ->
                val dayLabel = dayNames.getOrElse(item.dayOfWeek - 1) { "Monday" }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 16, 16, 16)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tvInfo = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "$dayLabel • ${item.startTime} to ${item.endTime}\n${item.title}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
                val btnEdit = Button(this).apply {
                    text = "Edit"
                    setOnClickListener {
                        showAddOrEditTemplateBlockDialog(item) { updatedBlock ->
                            templates[idx] = updatedBlock
                            renderGridList()
                        }
                    }
                }
                val btnDel = Button(this).apply {
                    text = "Del"
                    setOnClickListener {
                        templates.removeAt(idx)
                        renderGridList()
                    }
                }
                row.addView(tvInfo)
                row.addView(btnEdit)
                row.addView(btnDel)
                listContainer.addView(row)
            }
        }

        val btnAddBlock = Button(this).apply {
            text = "+ Add Weekly Class"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                showAddOrEditTemplateBlockDialog(null) { newBlock ->
                    templates.add(newBlock)
                    renderGridList()
                }
            }
        }

        renderGridList()
        rootLayout.addView(btnAddBlock)
        rootLayout.addView(scroll)

        AlertDialog.Builder(this)
            .setTitle("7-Day Weekly Timetable")
            .setView(rootLayout)
            .setPositiveButton("SAVE TIMETABLE") { _, _ ->
                val semName = findViewById<EditText>(R.id.etSemesterName).text.toString().ifBlank { "Fall 2026" }
                val startStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                CoroutineScope(Dispatchers.IO).launch {
                    TimetableEngine.buildAndSaveSemester(
                        this@MainActivity,
                        semName,
                        startStr,
                        selectedSemesterEndDate,
                        templates,
                        isRescanOverwrite = true
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Weekly Timetable Saved!", Toast.LENGTH_LONG).show()
                        switchTab(2)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // Uses a Day Dropdown + Android Clock Picker (Zero Manual Number Typing)
    private fun showAddOrEditTemplateBlockDialog(
        existing: TemplateEvent?,
        onSaved: (TemplateEvent) -> Unit
    ) {
        val dayNames = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val tvDayLabel = TextView(this).apply {
            text = "Select Day of Week:"
            setTextColor(Color.LTGRAY)
        }
        val spinnerDay = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dayNames)
            setSelection((existing?.dayOfWeek ?: 1) - 1)
        }

        val etTitle = EditText(this).apply {
            hint = "Subject Name (e.g., Physics Lecture)"
            setText(existing?.title ?: "")
        }

        var chosenStart = existing?.startTime ?: "09:00"
        var chosenEnd = existing?.endTime ?: "10:00"

        val btnStart = Button(this).apply {
            text = "Start Time: $chosenStart (Tap to Pick)"
            isAllCaps = false
            setOnClickListener {
                openClockPicker(chosenStart) { picked ->
                    chosenStart = picked
                    text = "Start Time: $chosenStart (Tap to Pick)"
                }
            }
        }

        val btnEnd = Button(this).apply {
            text = "End Time: $chosenEnd (Tap to Pick)"
            isAllCaps = false
            setOnClickListener {
                openClockPicker(chosenEnd) { picked ->
                    chosenEnd = picked
                    text = "End Time: $chosenEnd (Tap to Pick)"
                }
            }
        }

        layout.addView(tvDayLabel)
        layout.addView(spinnerDay)
        layout.addView(etTitle)
        layout.addView(btnStart)
        layout.addView(btnEnd)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Weekly Class" else "Edit Weekly Class")
            .setView(layout)
            .setPositiveButton("Save Class") { _, _ ->
                val day = spinnerDay.selectedItemPosition + 1
                val title = etTitle.text.toString().trim().ifBlank { "Lecture" }
                onSaved(TemplateEvent(0, 1, day, title, chosenStart, chosenEnd))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openClockPicker(currentHhMm: String, onTimePicked: (String) -> Unit) {
        val parts = currentHhMm.split(":")
        val initHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val initMin = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val formatted = String.format("%02d:%02d", hourOfDay, minute)
                onTimePicked(formatted)
            },
            initHour,
            initMin,
            false
        ).show()
    }

    // ================= 3. TODAY DASHBOARD & LIVE COUNTDOWN =================
    private fun bindDashboardTab() {
        findViewById<Button>(R.id.btnBannerFixPermissions).setOnClickListener {
            switchTab(4)
        }
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
            CoroutineScope(Dispatchers.IO).launch {
                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                withContext(Dispatchers.Main) {
                    refreshAllScreens()
                }
            }
        }
    }

    // ================= 4. CALENDAR & HOLIDAY TAB =================
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
                    refreshAllScreens()
                }
            }
        }

        findViewById<Button>(R.id.btnRestoreHoliday).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                TimetableEngine.restoreHolidayFromTemplate(this@MainActivity, selectedCalendarDate)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Restored classes for $selectedCalendarDate", Toast.LENGTH_SHORT).show()
                    refreshAllScreens()
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
                        setPadding(16, 16, 16, 16)
                        setBackgroundColor(Color.parseColor("#1E2028"))
                        gravity = Gravity.CENTER_VERTICAL
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
                        text = "Del"
                        setOnClickListener {
                            CoroutineScope(Dispatchers.IO).launch {
                                AppDatabase.getDatabase(this@MainActivity).scheduleDao().deleteSingleEvent(ev)
                                AutomationEngine.evaluateAndScheduleNext(this@MainActivity)
                                withContext(Dispatchers.Main) { refreshAllScreens() }
                            }
                        }
                    }
                    row.addView(tv)
                    row.addView(btnEdit)
                    row.addView(btnDel)
                    container.addView(row)
                }
            }
        }
    }

    // Uses Android Clock Picker for Single-Day Extra Classes
    private fun showSingleEventDialog(targetDate: String, existing: ScheduleEvent?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        val etTitle = EditText(this).apply {
            hint = "Class / Event Name (e.g., Extra Lab)"
            setText(existing?.title ?: "")
        }

        var chosenStart = existing?.startTime ?: "14:00"
        var chosenEnd = existing?.endTime ?: "15:00"

        val btnStart = Button(this).apply {
            text = "Start Time: $chosenStart (Tap to Pick)"
            isAllCaps = false
            setOnClickListener {
                openClockPicker(chosenStart) { picked ->
                    chosenStart = picked
                    text = "Start Time: $chosenStart (Tap to Pick)"
                }
            }
        }

        val btnEnd = Button(this).apply {
            text = "End Time: $chosenEnd (Tap to Pick)"
            isAllCaps = false
            setOnClickListener {
                openClockPicker(chosenEnd) { picked ->
                    chosenEnd = picked
                    text = "End Time: $chosenEnd (Tap to Pick)"
                }
            }
        }

        layout.addView(etTitle)
        layout.addView(btnStart)
        layout.addView(btnEnd)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Class ($targetDate)" else "Edit Class ($targetDate)")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().trim().ifBlank { "Extra Class" }
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = AppDatabase.getDatabase(this@MainActivity).scheduleDao()
                    if (existing == null) {
                        dao.insertSingleEvent(ScheduleEvent(0, 1, targetDate, title, chosenStart, chosenEnd, true))
                    } else {
                        dao.updateEvent(existing.copy(title = title, startTime = chosenStart, endTime = chosenEnd))
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

    // ================= 5. SETTINGS & PERMISSIONS TAB =================
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
                withContext(Dispatchers.Main) { refreshAllScreens() }
            }
        }

        findViewById<Button>(R.id.btnForceSafeZoneNow).setOnClickListener {
            val currentSsid = AutomationEngine.getCurrentWifiSsid(this)
            if (currentSsid != null) {
                prefs.edit().putString("learned_safe_zone", currentSsid).apply()
                refreshAllScreens()
                Toast.makeText(this, "Locked '$currentSsid' as Hostel Safe Zone!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Connect to Wi-Fi and grant Location permission (#4 above) first.", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnGrantDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantExactAlarm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } else {
                    Toast.makeText(this, "Exact Alarms are already allowed!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Automatically granted on your Android version!", Toast.LENGTH_SHORT).show()
            }
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
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                101
            )
        }
    }

    // ================= 6. MASTER UI SYNC =================
    private fun refreshAllScreens() {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val prefs = getSharedPreferences("smart_brain_prefs", Context.MODE_PRIVATE)

        val learnedWifi = prefs.getString("learned_safe_zone", "Not Set Yet (3:00 AM Auto-Learn Active)")
        findViewById<TextView>(R.id.tvSafeZoneStatus).text = "Hostel Safe Zone Wi-Fi: $learnedWifi"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val dndOk = nm.isNotificationPolicyAccessGranted
        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // Show or hide the First-Launch Permission Banner on the Today Dashboard
        val banner = findViewById<LinearLayout>(R.id.cardPermissionBanner)
        banner.visibility = if (!dndOk || !exactOk) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tvHealthStatus).text =
            "1. Do Not Disturb (Ringer): ${if (dndOk) "Granted" else "MISSING (Required)"}\n" +
            "2. Exact On-Time Alarms: ${if (exactOk) "Granted" else "MISSING (Required)"}\n" +
            "3. Battery Optimization Bypass: ${if (battOk) "Active" else "Recommended"}\n" +
            "4. Camera (${if (camOk) "OK" else "Missing"}) & Wi-Fi Location (${if (locOk) "OK" else "Missing"})"

        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(this@MainActivity).scheduleDao()
            val activeSem = dao.getActiveSemester(todayStr)
            val todayEvents = dao.getEventsForDate(todayStr)

            withContext(Dispatchers.Main) {
                val tvSemInfo = findViewById<TextView>(R.id.tvActiveSemesterInfo)
                val tvShare = findViewById<TextView>(R.id.tvShareCodeDisplay)
                val cardEmpty = findViewById<LinearLayout>(R.id.cardEmptyState)
                val btnPickEndDate = findViewById<Button>(R.id.btnPickEndDate)

                if (activeSem != null) {
                    tvSemInfo.text = "Active Semester: ${activeSem.semesterName}\n(${activeSem.startDate} to ${activeSem.endDate})"
                    tvShare.text = activeSem.shareCode
                    selectedSemesterEndDate = activeSem.endDate
                    btnPickEndDate.text = "Semester End Date: $selectedSemesterEndDate (Tap to Pick)"
                    cardEmpty.visibility = View.GONE
                } else {
                    tvSemInfo.text = "No Active Semester Set Up Yet"
                    cardEmpty.visibility = View.VISIBLE
                }

                findViewById<TextView>(R.id.tvCurrentModeStatus).text =
                    prefs.getString("current_mode", "Normal Mode (Ringing)")
                findViewById<TextView>(R.id.tvTodayDateHeader).text = "Today's Classes ($todayStr)"

                val tvCountdownLabel = findViewById<TextView>(R.id.tvCountdownLabel)
                val tvSubtitle = findViewById<TextView>(R.id.tvNextEventSubtitle)

                // Check if a class is happening right now OR starting later today
                val activeEv = todayEvents.firstOrNull { it.isEnabled && nowStr >= it.startTime && nowStr < it.endTime }
                val nextEv = todayEvents.firstOrNull { it.isEnabled && it.startTime > nowStr }

                if (activeEv != null) {
                    tvCountdownLabel.text = "CURRENT CLASS ENDS IN"
                    nextTargetTime = runCatching {
                        LocalTime.parse(activeEv.endTime, DateTimeFormatter.ofPattern("HH:mm"))
                    }.getOrNull()
                    tvSubtitle.text = "In progress: ${activeEv.title} (Ends at ${activeEv.endTime})"
                } else if (nextEv != null) {
                    tvCountdownLabel.text = "NEXT CLASS STARTS IN"
                    nextTargetTime = runCatching {
                        LocalTime.parse(nextEv.startTime, DateTimeFormatter.ofPattern("HH:mm"))
                    }.getOrNull()
                    tvSubtitle.text = "Up next at ${nextEv.startTime}: ${nextEv.title}"
                } else {
                    tvCountdownLabel.text = "ALL DONE FOR TODAY"
                    nextTargetTime = null
                    tvSubtitle.text = "No more classes remaining today"
                }

                val containerToday = findViewById<LinearLayout>(R.id.containerTodayEvents)
                containerToday.removeAllViews()

                if (todayEvents.isEmpty()) {
                    val tvNone = TextView(this@MainActivity).apply {
                        text = "No classes scheduled for today."
                        setTextColor(Color.LTGRAY)
                        setPadding(8, 16, 8, 16)
                    }
                    containerToday.addView(tvNone)
                }

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
                                if (!ev.isEnabled) " (Skipped Today)" else ""
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
