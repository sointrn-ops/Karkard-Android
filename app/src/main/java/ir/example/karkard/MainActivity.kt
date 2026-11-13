package ir.example.karkard

import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

@Entity(tableName = "daily_entries", primaryKeys = ["weekKey", "dayIndex"])
data class DailyEntry(
    val weekKey: String,
    val dayIndex: Int,
    val dateText: String = "",
    val overtime: String = "",
    val advance: String = "",
    val report: String = ""
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM daily_entries WHERE weekKey = :week ORDER BY dayIndex")
    suspend fun getWeek(week: String): List<DailyEntry>

    @Query("SELECT DISTINCT weekKey FROM daily_entries ORDER BY weekKey DESC")
    suspend fun getWeeks(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: DailyEntry)

    @Query("DELETE FROM advance_records WHERE weekKey = :week AND dayIndex = :day")
    suspend fun clearAdvances(week: String, day: Int)
    @Query("DELETE FROM overtime_records WHERE weekKey = :week AND dayIndex = :day")
    suspend fun clearOvertime(week: String, day: Int)
    @Insert suspend fun addAdvances(items: List<AdvanceRecord>)
    @Insert suspend fun addOvertime(items: List<OvertimeRecord>)
    @Query("SELECT * FROM advance_records WHERE weekKey = :week AND dayIndex = :day ORDER BY id")
    suspend fun getAdvances(week: String, day: Int): List<AdvanceRecord>
    @Query("SELECT * FROM overtime_records WHERE weekKey = :week AND dayIndex = :day ORDER BY id")
    suspend fun getOvertime(week: String, day: Int): List<OvertimeRecord>
    @Query("SELECT * FROM deleted_entries ORDER BY deletedAt DESC")
    suspend fun getTrash(): List<DeletedEntry>
    @Insert suspend fun addToTrash(entry: DeletedEntry)
    @Query("DELETE FROM deleted_entries WHERE weekKey = :week AND dayIndex = :day AND deletedAt = :deletedAt")
    suspend fun removeFromTrash(week: String, day: Int, deletedAt: Long)
    @Query("DELETE FROM daily_entries WHERE weekKey = :week AND dayIndex = :day")
    suspend fun deleteDay(week: String, day: Int)
    @Query("SELECT * FROM daily_entries WHERE dateText LIKE '%' || :query || '%' OR report LIKE '%' || :query || '%' OR advance LIKE '%' || :query || '%' OR overtime LIKE '%' || :query || '%' ORDER BY weekKey DESC, dayIndex")
    suspend fun searchEntries(query: String): List<DailyEntry>
    @Query("SELECT * FROM daily_entries WHERE substr(replace(dateText, '-', '/'), 1, 7) = :month ORDER BY dayIndex")
    suspend fun getMonth(month: String): List<DailyEntry>

    @Transaction
    suspend fun saveDay(entry: DailyEntry) {
        save(entry)
        clearAdvances(entry.weekKey, entry.dayIndex)
        clearOvertime(entry.weekKey, entry.dayIndex)
        val advances = entry.advance.lines().map { it.trim() }.filter { it.isNotBlank() }
            .map { AdvanceRecord(weekKey = entry.weekKey, dayIndex = entry.dayIndex, amount = it) }
        val overtimeItems = entry.overtime.lines().map { it.trim() }.filter { it.isNotBlank() }
            .map { OvertimeRecord(weekKey = entry.weekKey, dayIndex = entry.dayIndex, duration = it) }
        if (advances.isNotEmpty()) addAdvances(advances)
        if (overtimeItems.isNotEmpty()) addOvertime(overtimeItems)
    }

    @Transaction
    suspend fun moveToTrash(entry: DailyEntry, deletedAt: Long) {
        addToTrash(DeletedEntry(entry.weekKey, entry.dayIndex, entry.dateText, entry.overtime, entry.advance, entry.report, deletedAt))
        deleteDay(entry.weekKey, entry.dayIndex)
        clearAdvances(entry.weekKey, entry.dayIndex)
        clearOvertime(entry.weekKey, entry.dayIndex)
    }

    @Transaction
    suspend fun restore(entry: DeletedEntry) {
        saveDay(DailyEntry(entry.weekKey, entry.dayIndex, entry.dateText, entry.overtime, entry.advance, entry.report))
        removeFromTrash(entry.weekKey, entry.dayIndex, entry.deletedAt)
    }

    @Query("SELECT * FROM daily_entries ORDER BY weekKey DESC, dayIndex")
    suspend fun getAll(): List<DailyEntry>

    @Query("""
        SELECT COUNT(*) FROM daily_entries
        WHERE weekKey = :week AND dayIndex != :day
        AND dateText = :date AND advance = :advance
        AND overtime = :overtime AND report = :report
    """)
    suspend fun countDuplicate(
        week: String, day: Int, date: String,
        advance: String, overtime: String, report: String
    ): Int
}

@Entity(tableName = "advance_records")
data class AdvanceRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val weekKey: String, val dayIndex: Int, val amount: String, val note: String = "")

@Entity(tableName = "overtime_records")
data class OvertimeRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val weekKey: String, val dayIndex: Int, val duration: String, val note: String = "")

@Entity(tableName = "deleted_entries", primaryKeys = ["weekKey", "dayIndex", "deletedAt"])
data class DeletedEntry(val weekKey: String, val dayIndex: Int, val dateText: String, val overtime: String, val advance: String, val report: String, val deletedAt: Long)

@Database(entities = [DailyEntry::class, AdvanceRecord::class, OvertimeRecord::class, DeletedEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS advance_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, weekKey TEXT NOT NULL, dayIndex INTEGER NOT NULL, amount TEXT NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS overtime_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, weekKey TEXT NOT NULL, dayIndex INTEGER NOT NULL, duration TEXT NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_entries (weekKey TEXT NOT NULL, dayIndex INTEGER NOT NULL, dateText TEXT NOT NULL, overtime TEXT NOT NULL, advance TEXT NOT NULL, report TEXT NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(weekKey, dayIndex, deletedAt))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_advance_records_weekKey_dayIndex ON advance_records(weekKey, dayIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_overtime_records_weekKey_dayIndex ON overtime_records(weekKey, dayIndex)")
            }
        }
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "karkard.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).entryDao()
        setContent { KarkardScreen(dao) }
    }
}

private val days = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KarkardScreen(dao: EntryDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themePrefs = remember { context.getSharedPreferences("karban_settings", android.content.Context.MODE_PRIVATE) }
    var isDarkMode by remember { mutableStateOf(themePrefs.getBoolean("dark_mode", false)) }
    val weekKey = remember { mutableStateOf(SimpleDateFormat("yyyy-'W'ww", Locale.US).format(Date())) }
    var rows by remember { mutableStateOf(days.indices.map { DailyEntry(weekKey.value, it) }) }
    var allWeeks by remember { mutableStateOf(emptyList<String>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            isLoading = true
            try {
                val saved = dao.getWeek(weekKey.value).associateBy { it.dayIndex }
                rows = days.indices.map { saved[it] ?: DailyEntry(weekKey.value, it) }
                allWeeks = dao.getWeeks()
            } catch (e: Exception) {
                message = "بارگذاری اطلاعات ناموفق بود."
            } finally {
                isLoading = false
            }
        }
    }
    LaunchedEffect(weekKey.value) { refresh() }

    val persianLocale = remember { Locale("fa", "IR") }
    val weekRangeLabel = remember {
        val calendar = Calendar.getInstance(persianLocale)
        calendar.firstDayOfWeek = Calendar.SATURDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
        val start = calendar.time
        calendar.add(Calendar.DAY_OF_MONTH, 5)
        val end = calendar.time
        val formatter = SimpleDateFormat("yyyy/MM/dd", persianLocale)
        "${formatter.format(start)} تا ${formatter.format(end)}"
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val entries = dao.getAll()
            val pdf = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdf.startPage(pageInfo)
            val paint = Paint().apply { textSize = 13f; color = android.graphics.Color.BLACK }
            var y = 48f
            page.canvas.drawText("KARBAN - Work Report", 40f, y, paint); y += 30f
            entries.forEach { e ->
                val line = "${e.weekKey} | ${days.getOrElse(e.dayIndex) { "" }} | Date: ${e.dateText} | OT: ${e.overtime} | Advance: ${e.advance}"
                page.canvas.drawText(line.take(95), 40f, y, paint); y += 22f
                if (y > 790f) return@forEach
                if (e.report.isNotBlank()) { page.canvas.drawText("Report: ${e.report.take(75)}", 55f, y, paint); y += 20f }
            }
            pdf.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
            pdf.close()
            message = "فایل PDF ذخیره شد."
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val entries = dao.getAll()
            val csv = buildString {
                append('\uFEFF')
                append("هفته,روز,تاریخ,اضافه‌کار,مساعده,گزارش\n")
                entries.forEach { e ->
                    val fields = listOf(e.weekKey, days.getOrElse(e.dayIndex) { "" }, e.dateText, e.overtime, e.advance, e.report)
                    append(fields.joinToString(",") { "\"" + it.replace("\"", "\"\"").replace("\n", " ") + "\"" })
                    append("\n")
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            message = "فایل جدول قابل باز شدن در Excel ذخیره شد."
        }
    }

    val appColors = if (isDarkMode) darkColorScheme(
        primary = Color(0xFF8BC7B1),
        secondary = Color(0xFFE0B96B),
        background = Color(0xFF101A17),
        surface = Color(0xFF1B2924),
        onBackground = Color(0xFFE8EFEA),
        onSurface = Color(0xFFE8EFEA)
    ) else lightColorScheme(
        primary = Color(0xFF23463D),
        secondary = Color(0xFFB18B55),
        background = Color(0xFFF5F6F3),
        surface = Color.White
    )

    MaterialTheme(colorScheme = appColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("KARBAN", fontWeight = FontWeight.Bold)
                        Text("مدیریت اطلاعات هفتگی", style = MaterialTheme.typography.labelSmall)
                    } },
                    actions = {
                        TextButton(onClick = {
                            isDarkMode = !isDarkMode
                            themePrefs.edit().putBoolean("dark_mode", isDarkMode).apply()
                        }) { Text(if (isDarkMode) "حالت روز" else "حالت شب") }
                        TextButton(onClick = { showAbout = true }) { Text("درباره برنامه") }
                        TextButton(onClick = { showHistory = !showHistory; refresh() }) { Text("آرشیو") }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            if (isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 28.dp, top = 8.dp)
            ) {
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (showHistory) "انتخاب هفته ذخیره‌شده" else "هفته جاری", fontWeight = FontWeight.Bold)
                            if (showHistory) {
                                allWeeks.forEach { week ->
                                    TextButton(onClick = {
                                        weekKey.value = week
                                        showHistory = false
                                        refresh()
                                    }) { Text(week) }
                                }
                                if (allWeeks.isEmpty()) Text("هنوز اطلاعاتی ذخیره نشده است.")
                            } else {
                                Text("بازه هفته (شمسی): $weekRangeLabel", color = MaterialTheme.colorScheme.secondary)
                                Text("اطلاعات هر روز را وارد کنید و دکمه ذخیره همان روز را بزنید.",
                                    style = MaterialTheme.typography.bodySmall)
                                val incompleteCount = rows.count {
                                    (it.dateText.isNotBlank() || it.advance.isNotBlank() ||
                                        it.overtime.isNotBlank() || it.report.isNotBlank()) &&
                                        it.dateText.isBlank()
                                }
                                if (incompleteCount > 0) {
                                    Text(
                                        "هشدار: برای ${incompleteCount} روز اطلاعاتی وارد شده اما تاریخ ثبت نشده است.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
                if (!showHistory) {
                    items(rows, key = { it.dayIndex }) { entry ->
                        var date by remember(entry.weekKey, entry.dayIndex) { mutableStateOf(entry.dateText) }
                        var overtime by remember(entry.weekKey, entry.dayIndex) { mutableStateOf(entry.overtime) }
                        var advance by remember(entry.weekKey, entry.dayIndex) { mutableStateOf(entry.advance) }
                        var report by remember(entry.weekKey, entry.dayIndex) { mutableStateOf(entry.report) }
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(days[entry.dayIndex], fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                OutlinedTextField(date, { date = it }, label = { Text("تاریخ شمسی") },
                                    placeholder = { Text("مثلاً ۱۴۰۵/۰۸/۲۲") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(
                                    overtime, { overtime = it },
                                    label = { Text("اضافه‌کاری (هر نوبت در یک خط)") },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                OutlinedTextField(
                                    advance, { advance = it },
                                    label = { Text("مساعده (هر مورد در یک خط)") },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                OutlinedTextField(report, { report = it }, label = { Text("گزارشات روز") },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (entry.dayIndex > 0) {
                                        OutlinedButton(
                                            onClick = {
                                                val previous = rows.firstOrNull { it.dayIndex == entry.dayIndex - 1 }
                                                if (previous != null) {
                                                    date = previous.dateText
                                                    overtime = previous.overtime
                                                    advance = previous.advance
                                                    report = previous.report
                                                    message = "اطلاعات روز قبل کپی شد؛ تاریخ را بررسی و سپس ذخیره کنید."
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("کپی روز قبل") }
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val duplicateCount = if (date.isNotBlank()) {
                                                    dao.countDuplicate(
                                                        weekKey.value, entry.dayIndex, date,
                                                        advance, overtime, report
                                                    )
                                                } else 0
                                                if (duplicateCount > 0) {
                                                    message = "این اطلاعات برای روز دیگری در همین هفته ثبت شده است؛ ثبت تکراری متوقف شد."
                                                } else {
                                                    dao.saveDay(DailyEntry(weekKey.value, entry.dayIndex, date, overtime, advance, report))
                                                    message = "اطلاعات روز ذخیره شد."
                                                    allWeeks = dao.getWeeks()
                                                    refresh()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("ذخیره اطلاعات") }
                                }
                            }
                        }
                    }
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("گزارش‌گیری و خروجی", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { pdfLauncher.launch("گزارش-کارکرد.pdf") }, modifier = Modifier.weight(1f)) {
                                        Text("خروجی PDF")
                                    }
                                    OutlinedButton(onClick = { csvLauncher.launch("گزارش-کارکرد.csv") }, modifier = Modifier.weight(1f)) {
                                        Text("خروجی Excel")
                                    }
                                }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("درباره برنامه", fontWeight = FontWeight.Bold)
                        Text("KARBAN", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("نسخه برنامه: 1.0")
                        Text("طراحی و توسعه: محمد جواد احمدی", fontWeight = FontWeight.SemiBold)
                        HorizontalDivider()
                        Text("ارتباط با سازنده", fontWeight = FontWeight.Bold)
                        Text("برای تماس، یکی از شماره‌های زیر را انتخاب کنید:")
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:09017975131"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("۰۹۰۱۷۹۷۵۱۳۱  •  تماس") }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:09904711802"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("۰۹۹۰۴۷۱۱۸۰۲  •  تماس") }
                        Text("© تمامی حقوق محفوظ است", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) { Text("بستن") }
                }
            )
        }
    }
}
