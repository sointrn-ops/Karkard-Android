package ir.example.karkard

import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    @Query("SELECT * FROM daily_entries ORDER BY weekKey DESC, dayIndex")
    suspend fun getAll(): List<DailyEntry>
}

@Database(entities = [DailyEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "karkard.db"
                ).build().also { INSTANCE = it }
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
    val weekKey = remember { mutableStateOf(SimpleDateFormat("yyyy-'W'ww", Locale.US).format(Date())) }
    var rows by remember { mutableStateOf(days.indices.map { DailyEntry(weekKey.value, it) }) }
    var allWeeks by remember { mutableStateOf(emptyList<String>()) }
    var showHistory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val saved = dao.getWeek(weekKey.value).associateBy { it.dayIndex }
            rows = days.indices.map { saved[it] ?: DailyEntry(weekKey.value, it) }
            allWeeks = dao.getWeeks()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val entries = dao.getAll()
            val pdf = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdf.startPage(pageInfo)
            val paint = Paint().apply { textSize = 13f; color = android.graphics.Color.BLACK }
            var y = 48f
            page.canvas.drawText("Karkard - Work Report", 40f, y, paint); y += 30f
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

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF23463D), secondary = Color(0xFFB18B55),
        background = Color(0xFFF5F6F3), surface = Color.White
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("ثبت کارکرد", fontWeight = FontWeight.Bold)
                        Text("مدیریت اطلاعات هفتگی", style = MaterialTheme.typography.labelSmall)
                    } },
                    actions = {
                        TextButton(onClick = { showHistory = !showHistory; refresh() }) { Text("آرشیو") }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            LazyColumn(
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
                                Text("شناسه هفته: ${weekKey.value}", color = MaterialTheme.colorScheme.secondary)
                                Text("اطلاعات هر روز را وارد کنید و دکمه ذخیره همان روز را بزنید.",
                                    style = MaterialTheme.typography.bodySmall)
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
                                OutlinedTextField(overtime, { overtime = it }, label = { Text("اضافه‌کار") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(advance, { advance = it }, label = { Text("مساعده") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(report, { report = it }, label = { Text("گزارشات روز") },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            dao.save(DailyEntry(weekKey.value, entry.dayIndex, date, overtime, advance, report))
                                            message = "اطلاعات ${days[entry.dayIndex]} ذخیره شد."
                                            allWeeks = dao.getWeeks()
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) { Text("ذخیره اطلاعات") }
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
    }
}
