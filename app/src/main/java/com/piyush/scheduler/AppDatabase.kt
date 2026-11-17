package com.piyush.scheduler

import android.content.Context
import androidx.room.*

// 1. Tracks the active semester and auto-purges when expired
@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true) val semesterId: Int = 0,
    val semesterName: String,
    val startDate: String, // Format: YYYY-MM-DD
    val endDate: String,   // Format: YYYY-MM-DD
    val shareCode: String  // Compressed Base64 peer-to-peer string
)

// 2. Stores the 7-day baseline grid (used to restore unmarked holidays & share codes)
@Entity(tableName = "master_template")
data class TemplateEvent(
    @PrimaryKey(autoGenerate = true) val templateId: Int = 0,
    val semesterId: Int,
    val dayOfWeek: Int,    // 1 = Monday ... 7 = Sunday
    val title: String,
    val startTime: String, // Format: HH:mm (24-hour, e.g., "08:00")
    val endTime: String    // Format: HH:mm (24-hour, e.g., "10:00")
)

// 3. Flattened daily instances for granular edits, toggles, and holiday deletion
@Entity(tableName = "schedule_events")
data class ScheduleEvent(
    @PrimaryKey(autoGenerate = true) val eventId: Int = 0,
    val semesterId: Int,
    val date: String,      // Format: YYYY-MM-DD (e.g., "2026-11-17")
    val title: String,
    val startTime: String, // Format: HH:mm
    val endTime: String,   // Format: HH:mm
    val isEnabled: Boolean = true
)

@Dao
interface ScheduleDao {
    // --- SEMESTER LIFECYCLE ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterEntity): Long

    @Query("SELECT * FROM semesters WHERE endDate >= :todayDate ORDER BY semesterId DESC LIMIT 1")
    suspend fun getActiveSemester(todayDate: String): SemesterEntity?

    @Query("DELETE FROM semesters WHERE endDate < :todayDate")
    suspend fun purgeExpiredSemesters(todayDate: String)

    @Query("DELETE FROM schedule_events WHERE semesterId = :semId AND date >= :fromDate")
    suspend fun clearFutureEventsForRescan(semId: Int, fromDate: String)

    // --- MASTER 7-DAY TEMPLATE ---
    @Query("DELETE FROM master_template WHERE semesterId = :semId")
    suspend fun clearTemplatesForSemester(semId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<TemplateEvent>)

    @Query("SELECT * FROM master_template WHERE semesterId = :semId ORDER BY dayOfWeek ASC, startTime ASC")
    suspend fun getMasterTemplate(semId: Int): List<TemplateEvent>

    @Query("SELECT * FROM master_template WHERE semesterId = :semId AND dayOfWeek = :day ORDER BY startTime ASC")
    suspend fun getTemplateForDay(semId: Int, day: Int): List<TemplateEvent>

    // --- DAILY DASHBOARD & CALENDAR DRILL-DOWN ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<ScheduleEvent>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleEvent(event: ScheduleEvent)

    @Query("SELECT * FROM schedule_events WHERE date = :targetDate ORDER BY startTime ASC")
    suspend fun getEventsForDate(targetDate: String): List<ScheduleEvent>

    @Query("UPDATE schedule_events SET isEnabled = :enabled WHERE eventId = :id")
    suspend fun toggleEventStatus(id: Int, enabled: Boolean)

    @Update
    suspend fun updateEvent(event: ScheduleEvent)

    @Delete
    suspend fun deleteSingleEvent(event: ScheduleEvent)

    @Query("DELETE FROM schedule_events WHERE date = :holidayDate")
    suspend fun deleteAllEventsOnDate(holidayDate: String)
}

@Database(
    entities = [SemesterEntity::class, TemplateEvent::class, ScheduleEvent::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_ringer_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
