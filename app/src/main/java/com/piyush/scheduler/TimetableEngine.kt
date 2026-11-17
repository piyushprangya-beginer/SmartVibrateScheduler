package com.piyush.scheduler

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

object TimetableEngine {

    // 1. OFFLINE ML KIT TIMETABLE SCANNER
    fun scanTimetableBitmap(
        bitmap: Bitmap,
        onSuccess: (List<TemplateEvent>) -> Unit,
        onError: (String) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val parsedGrid = parseCoordinatesToGrid(visionText)
                onSuccess(parsedGrid)
            }
            .addOnFailureListener { e ->
                onError(e.localizedMessage ?: "OCR Scan Failed")
            }
    }

    private fun parseCoordinatesToGrid(visionText: Text): List<TemplateEvent> {
        val results = mutableListOf<TemplateEvent>()
        val dayKeywords = mapOf(
            "MON" to 1, "TUE" to 2, "WED" to 3,
            "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7
        )
        val timeRegex = Regex("""(\d{1,2})[:.](\d{2})""")

        val dayLocations = mutableMapOf<Int, Pair<Int, Int>>()
        val timeLocations = mutableListOf<Triple<String, Int, Int>>()
        val classBlocks = mutableListOf<Triple<String, Int, Int>>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox ?: continue
                val cx = rect.centerX()
                val cy = rect.centerY()
                val rawText = line.text.trim()
                val upper = rawText.uppercase()

                val matchedDay = dayKeywords.entries.firstOrNull { upper.contains(it.key) }
                if (matchedDay != null) {
                    dayLocations[matchedDay.value] = Pair(cx, cy)
                    continue
                }

                val timeMatch = timeRegex.find(rawText)
                if (timeMatch != null) {
                    val h = (timeMatch.groupValues[1].toIntOrNull() ?: 8).coerceIn(0, 23).toString().padStart(2, '0')
                    val m = (timeMatch.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59).toString().padStart(2, '0')
                    timeLocations.add(Triple("$h:$m", cx, cy))
                    continue
                }

                if (rawText.length >= 3) {
                    classBlocks.add(Triple(rawText, cx, cy))
                }
            }
        }

        for ((title, cx, cy) in classBlocks) {
            val closestDay = dayLocations.minByOrNull { (_, pos) ->
                abs(cx - pos.first) + abs(cy - pos.second) / 2
            }?.key ?: 1

            val closestTime = timeLocations.minByOrNull { (_, tx, ty) ->
                abs(cx - tx) + abs(cy - ty)
            }?.first ?: "08:00"

            val startHour = closestTime.substringBefore(":").toIntOrNull() ?: 8
            val endHour = (startHour + 1).coerceAtMost(23).toString().padStart(2, '0')
            val minutes = closestTime.substringAfter(":", "00")

            results.add(
                TemplateEvent(
                    templateId = 0,
                    semesterId = 1,
                    dayOfWeek = closestDay,
                    title = title,
                    startTime = closestTime,
                    endTime = "$endHour:$minutes"
                )
            )
        }

        return results
    }

    // 2. SEMESTER INSTANCE GENERATOR & RE-SCAN OVERWRITE
    suspend fun buildAndSaveSemester(
        context: Context,
        semesterName: String,
        startDateStr: String,
        endDateStr: String,
        templates: List<TemplateEvent>,
        isRescanOverwrite: Boolean = false
    ): String {
        val dao = AppDatabase.getDatabase(context).scheduleDao()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        val safeStartDate = runCatching {
            LocalDate.parse(startDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrDefault(LocalDate.now())

        val safeEndDate = runCatching {
            LocalDate.parse(endDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrDefault(LocalDate.now().plusMonths(4))

        val finalStartStr = safeStartDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val finalEndStr = if (safeEndDate.isBefore(safeStartDate)) {
            safeStartDate.plusMonths(4).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } else {
            safeEndDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }

        val shareCode = encodeShareCode(semesterName, finalStartStr, finalEndStr, templates)

        val activeSem = dao.getActiveSemester(todayStr)
        val semId: Int = if (isRescanOverwrite && activeSem != null) {
            dao.clearFutureEventsForRescan(activeSem.semesterId, todayStr)
            dao.clearTemplatesForSemester(activeSem.semesterId)
            dao.insertSemester(
                activeSem.copy(
                    semesterName = semesterName,
                    endDate = finalEndStr,
                    shareCode = shareCode
                )
            )
            activeSem.semesterId
        } else {
            dao.insertSemester(
                SemesterEntity(
                    semesterName = semesterName,
                    startDate = finalStartStr,
                    endDate = finalEndStr,
                    shareCode = shareCode
                )
            ).toInt()
        }

        val cleanTemplates = templates.map { it.copy(templateId = 0, semesterId = semId) }
        dao.insertTemplates(cleanTemplates)

        val endDateParsed = LocalDate.parse(finalEndStr, DateTimeFormatter.ISO_LOCAL_DATE)
        val effectiveStart = if (isRescanOverwrite && safeStartDate.isBefore(LocalDate.now())) {
            LocalDate.now()
        } else {
            safeStartDate
        }

        val generatedEvents = mutableListOf<ScheduleEvent>()
        var cursorDate = effectiveStart
        while (!cursorDate.isAfter(endDateParsed)) {
            val dayOfWeek = cursorDate.dayOfWeek.value
            val dateStr = cursorDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            cleanTemplates.filter { it.dayOfWeek == dayOfWeek }.forEach { tmpl ->
                generatedEvents.add(
                    ScheduleEvent(
                        semesterId = semId,
                        date = dateStr,
                        title = tmpl.title,
                        startTime = tmpl.startTime,
                        endTime = tmpl.endTime,
                        isEnabled = true
                    )
                )
            }
            cursorDate = cursorDate.plusDays(1)
        }

        dao.insertEvents(generatedEvents)
        AutomationEngine.evaluateAndScheduleNext(context)
        return shareCode
    }

    // 3. REVERT HOLIDAY FROM 7-DAY MASTER TEMPLATE
    suspend fun restoreHolidayFromTemplate(context: Context, targetDateStr: String) {
        val dao = AppDatabase.getDatabase(context).scheduleDao()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val activeSem = dao.getActiveSemester(todayStr) ?: return
        val date = runCatching {
            LocalDate.parse(targetDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: return

        dao.deleteAllEventsOnDate(targetDateStr)
        val dayTemplates = dao.getTemplateForDay(activeSem.semesterId, date.dayOfWeek.value)

        val restored = dayTemplates.map { tmpl ->
            ScheduleEvent(
                semesterId = activeSem.semesterId,
                date = targetDateStr,
                title = tmpl.title,
                startTime = tmpl.startTime,
                endTime = tmpl.endTime,
                isEnabled = true
            )
        }
        dao.insertEvents(restored)
        AutomationEngine.evaluateAndScheduleNext(context)
    }

    // 4. SERVERLESS PEER-TO-PEER SHARE CODE (GZIP + BASE64)
    fun encodeShareCode(
        semName: String,
        startStr: String,
        endStr: String,
        templates: List<TemplateEvent>
    ): String {
        val root = JSONObject().apply {
            put("n", semName)
            put("s", startStr)
            put("e", endStr)
            val arr = JSONArray()
            templates.forEach { t ->
                arr.put(JSONObject().apply {
                    put("d", t.dayOfWeek)
                    put("t", t.title)
                    put("st", t.startTime)
                    put("et", t.endTime)
                })
            }
            put("g", arr)
        }

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(root.toString()) }
        val compressed = bos.toByteArray()
        return "RPCAU-" + Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    suspend fun importShareCode(context: Context, rawCode: String): Boolean {
        return try {
            val cleanBase64 = rawCode.trim().removePrefix("RPCAU-")
            val compressed = Base64.decode(cleanBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressed))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }

            val root = JSONObject(jsonStr)
            val semName = root.getString("n")
            val startStr = root.getString("s")
            val endStr = root.getString("e")
            val arr = root.getJSONArray("g")

            val templates = mutableListOf<TemplateEvent>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                templates.add(
                    TemplateEvent(
                        templateId = 0,
                        semesterId = 0,
                        dayOfWeek = obj.getInt("d"),
                        title = obj.getString("t"),
                        startTime = obj.getString("st"),
                        endTime = obj.getString("et")
                    )
                )
            }

            buildAndSaveSemester(context, semName, startStr, endStr, templates, isRescanOverwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }
}
