package ir.example.karkard

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

@Entity(tableName = "daily_entries", primaryKeys = ["weekKey", "dayIndex"])
data class DailyEntry(
    val weekKey: String, val dayIndex: Int, val dateText: String = "",
    val overtime: String = "", val advance: String = "", val report: String = ""
)

@Entity(tableName = "advance_records", indices = [Index(value = ["weekKey", "dayIndex"])])
data class AdvanceRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val weekKey: String, val dayIndex: Int, val amount: String, val note: String = "")

@Entity(tableName = "overtime_records", indices = [Index(value = ["weekKey", "dayIndex"])])
data class OvertimeRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val weekKey: String, val dayIndex: Int, val duration: String, val note: String = "")

@Entity(tableName = "deleted_entries", primaryKeys = ["weekKey", "dayIndex", "deletedAt"])
data class DeletedEntry(val weekKey: String, val dayIndex: Int, val dateText: String, val overtime: String, val advance: String, val report: String, val deletedAt: Long)

@Dao
interface EntryDao {
    @Query("SELECT * FROM daily_entries WHERE weekKey = :week ORDER BY dayIndex")
    suspend fun getWeek(week: String): List<DailyEntry>
    @Query("SELECT DISTINCT weekKey FROM daily_entries ORDER BY weekKey DESC")
    suspend fun getWeeks(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entry: DailyEntry)
    @Query("DELETE FROM advance_records WHERE weekKey = :week AND dayIndex = :day") suspend fun clearAdvances(week: String, day: Int)
    @Query("DELETE FROM overtime_records WHERE weekKey = :week AND dayIndex = :day") suspend fun clearOvertime(week: String, day: Int)
    @Insert suspend fun addAdvances(items: List<AdvanceRecord>)
    @Insert suspend fun addOvertime(items: List<OvertimeRecord>)
    @Query("SELECT * FROM deleted_entries ORDER BY deletedAt DESC") suspend fun getTrash(): List<DeletedEntry>
    @Insert suspend fun addToTrash(entry: DeletedEntry)
    @Query("DELETE FROM deleted_entries WHERE weekKey = :week AND dayIndex = :day AND deletedAt = :deletedAt") suspend fun removeFromTrash(week: String, day: Int, deletedAt: Long)
    @Query("DELETE FROM daily_entries WHERE weekKey = :week AND dayIndex = :day") suspend fun deleteDay(week: String, day: Int)
    @Query("SELECT * FROM daily_entries WHERE dateText LIKE '%' || :query || '%' OR report LIKE '%' || :query || '%' OR advance LIKE '%' || :query || '%' OR overtime LIKE '%' || :query || '%' ORDER BY weekKey DESC, dayIndex") suspend fun searchEntries(query: String): List<DailyEntry>
    @Query("SELECT * FROM daily_entries ORDER BY weekKey DESC, dayIndex") suspend fun getAll(): List<DailyEntry>
    @Query("SELECT COUNT(*) FROM daily_entries WHERE weekKey = :week AND dayIndex != :day AND dateText = :date AND advance = :advance AND overtime = :overtime AND report = :report")
    suspend fun countDuplicate(week: String, day: Int, date: String, advance: String, overtime: String, report: String): Int

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
}

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
                db.execSQL("""
                    WITH RECURSIVE split(weekKey, dayIndex, rest, item) AS (
                        SELECT weekKey, dayIndex, advance || char(10), ''
                        FROM daily_entries WHERE advance IS NOT NULL AND advance != ''
                        UNION ALL
                        SELECT weekKey, dayIndex,
                               substr(rest, instr(rest, char(10)) + 1),
                               trim(substr(rest, 1, instr(rest, char(10)) - 1))
                        FROM split WHERE rest != ''
                    )
                    INSERT INTO advance_records(weekKey, dayIndex, amount, note)
                    SELECT weekKey, dayIndex, item, '' FROM split WHERE item != ''
                """)
                db.execSQL("""
                    WITH RECURSIVE split(weekKey, dayIndex, rest, item) AS (
                        SELECT weekKey, dayIndex, overtime || char(10), ''
                        FROM daily_entries WHERE overtime IS NOT NULL AND overtime != ''
                        UNION ALL
                        SELECT weekKey, dayIndex,
                               substr(rest, instr(rest, char(10)) + 1),
                               trim(substr(rest, 1, instr(rest, char(10)) - 1))
                        FROM split WHERE rest != ''
                    )
                    INSERT INTO overtime_records(weekKey, dayIndex, duration, note)
                    SELECT weekKey, dayIndex, item, '' FROM split WHERE item != ''
                """)
            }
        }
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: android.content.Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "karkard.db")
                .addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KarbanApp(AppDatabase.get(this).entryDao()) }
    }
}

private val karbanDays = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه")
private val forest = Color(0xFF183C32)
private val leaf = Color(0xFF2D6554)
private val cream = Color(0xFFF4F2E9)
private val gold = Color(0xFFC49A58)
private val ink = Color(0xFF1C2D27)
private val muted = Color(0xFF718078)

private fun jalaliDate(calendar: Calendar): String {
 val gy=calendar.get(Calendar.YEAR); val gm=calendar.get(Calendar.MONTH)+1; val gd=calendar.get(Calendar.DAY_OF_MONTH)
 val md=intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334); val y=gy-1600; val m=gm-1; val d=gd-1
 var n=365*y+(y+3)/4-(y+99)/100+(y+399)/400+md[m]+d
 if(m>1 && (gy%4==0 && gy%100!=0 || gy%400==0)) n++
 var j=n-79; val cycle=j/12053; j%=12053
 var jy=979+33*cycle+4*(j/1461); j%=1461
 if(j>=366){jy+=(j-1)/365;j=(j-1)%365}
 val jm:Int; val jd:Int
 if(j<186){jm=1+j/31;jd=1+j%31}else{jm=7+(j-186)/30;jd=1+(j-186)%30}
 return String.format(Locale.US,"%04d/%02d/%02d",jy,jm,jd)
}
private fun jalaliWeekStart(offset:Int):Calendar {
 val c=Calendar.getInstance(); val dow=c.get(Calendar.DAY_OF_WEEK)
 val afterSaturday=if(dow==Calendar.SATURDAY) 0 else dow
 c.add(Calendar.DAY_OF_MONTH,-afterSaturday+offset*7); return c
}
private fun jalaliToGregorian(jy:Int,jm:Int,jd:Int):Calendar {
    val target=String.format(Locale.US,"%04d/%02d/%02d",jy,jm,jd)
    val cal=Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
    cal.clear()
    cal.set(jy+619, Calendar.JANUARY, 1)
    var low=cal.timeInMillis/86400000L
    cal.clear()
    cal.set(jy+623, Calendar.DECEMBER, 31)
    var high=cal.timeInMillis/86400000L
    while(low<=high) {
        val mid=(low+high) ushr 1
        val candidate=Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
        candidate.timeInMillis=mid*86400000L
        val value=jalaliDate(candidate)
        when {
            value<target -> low=mid+1
            value>target -> high=mid-1
            else -> return candidate
        }
    }
    throw IllegalArgumentException("Invalid Jalali date: $target")
}
private fun jalaliWeekStartFromKey(key:String):Calendar {
    val digits=key.removePrefix("J")
    require(digits.length==8 && digits.all { it.isDigit() }) { "Invalid week key" }
    return jalaliToGregorian(digits.substring(0,4).toInt(),digits.substring(4,6).toInt(),digits.substring(6,8).toInt())
}
private fun jalaliWeekKey(offset:Int):String = "J"+jalaliDate(jalaliWeekStart(offset)).replace("/","")
private fun jalaliDateForDay(weekKey:String,index:Int):String {
    val c=jalaliWeekStartFromKey(weekKey); c.add(Calendar.DAY_OF_MONTH,index); return jalaliDate(c)
}
private fun amountValue(raw: String): Double = raw
    .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
    .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
    .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
    .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
    .replace(",", "").replace("٬", "").filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KarbanApp(dao: EntryDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("karban_settings", android.content.Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
    var weekOffset by remember { mutableIntStateOf(0) }
    val week = remember { mutableStateOf(jalaliWeekKey(0)) }
    var rows by remember { mutableStateOf(karbanDays.indices.map { DailyEntry(week.value, it) }) }
    var weeks by remember { mutableStateOf(emptyList<String>()) }
    var trash by remember { mutableStateOf(emptyList<DeletedEntry>()) }
    var searchResults by remember { mutableStateOf(emptyList<DailyEntry>()) }
    var searchText by remember { mutableStateOf("") }
    var monthFilter by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("ثبت روزانه") }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val saved = dao.getWeek(week.value).associateBy { it.dayIndex }
                rows = karbanDays.indices.map { saved[it] ?: DailyEntry(week.value, it) }
                weeks = dao.getWeeks()
                trash = dao.getTrash()
            } catch (_: Exception) { notice = "بارگذاری اطلاعات با خطا روبه‌رو شد." }
            loading = false
        }
    }
    LaunchedEffect(week.value) { refresh() }
    LaunchedEffect(searchText, page) {
        if (page == "جست‌وجو" && searchText.isNotBlank()) {
            searchResults = dao.searchEntries(searchText.trim())
        } else if (page == "جست‌وجو") searchResults = emptyList()
    }

    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF9BCDB8), secondary = Color(0xFFE0BD7C),
        background = Color(0xFF101B17), surface = Color(0xFF1A2923),
        onBackground = Color(0xFFEAF0EB), onSurface = Color(0xFFEAF0EB)
    ) else lightColorScheme(
        primary = forest, secondary = gold, background = cream,
        surface = Color.White, onBackground = ink, onSurface = ink
    )

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val entries = dao.getAll()
            val pdf = PdfDocument()
            val paint = Paint().apply { textSize = 11f; color = android.graphics.Color.BLACK }
            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            var y = 48f
            fun startNextPage() {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                y = 48f
                page.canvas.drawText("KARBAN - Work Report (continued)", 36f, y, paint)
                y += 26f
            }
            page.canvas.drawText("KARBAN - Work Report", 36f, y, paint); y += 28f
            entries.forEach { e ->
                val lines = buildList {
                    add("${e.dateText} | ${karbanDays.getOrElse(e.dayIndex) { "" }}")
                    if (e.advance.isNotBlank()) add("Advance: ${e.advance.replace("\\n", "، ")}")
                    if (e.overtime.isNotBlank()) add("Overtime: ${e.overtime.replace("\\n", "، ")}")
                    if (e.report.isNotBlank()) add("Report: ${e.report.replace("\\n", " ").take(180)}")
                }
                lines.forEach { line ->
                    if (y > 790f) startNextPage()
                    page.canvas.drawText(line.take(95), 36f, y, paint)
                    y += 18f
                }
                y += 8f
            }
            pdf.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
            pdf.close()
            notice = "گزارش PDF ذخیره شد."
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val csv = buildString {
                append('\uFEFF'); append("هفته,روز,تاریخ,اضافه‌کاری,مساعده,گزارش\n")
                dao.getAll().forEach { e ->
                    append(listOf(e.weekKey, karbanDays.getOrElse(e.dayIndex) { "" }, e.dateText, e.overtime, e.advance, e.report)
                        .joinToString(",") { "\"${it.replace("\"", "\"\"").replace("\n", " ")}\"" })
                    append("\n")
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            notice = "فایل CSV برای باز کردن در Excel ذخیره شد."
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(Modifier.fillMaxWidth().background(forest).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("KARBAN", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text("سامانه هوشمند ثبت کارکرد", color = Color(0xFFD4E3DA), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { dark = !dark; prefs.edit().putBoolean("dark_mode", dark).apply() }) {
                            Text(if (dark) "☀ روز" else "☾ شب", color = Color.White)
                        }
                        TextButton(onClick = { about = true }) { Text("درباره", color = Color.White) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("پنل مدیریت کارکرد", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(if (page == "ثبت روزانه") "اطلاعات هفته جاری را مدیریت کنید" else page, color = Color(0xFFD4E3DA), style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(color = Color(0xFF31594C), shape = RoundedCornerShape(50)) {
                            Text("ذخیره‌سازی محلی", color = Color.White, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("ثبت روزانه", "گزارش‌ها", "جست‌وجو", "بازیافت").forEach { tab ->
                        NavigationBarItem(
                            selected = page == tab, onClick = { page = tab; if (tab == "بازیافت") refresh() },
                            icon = { Text(when(tab) { "ثبت روزانه" -> "▦"; "گزارش‌ها" -> "▤"; "جست‌وجو" -> "⌕"; else -> "↶" }, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(tab, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        ) { pad ->
            if (loading && page == "ثبت روزانه") {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else when (page) {
                "ثبت روزانه" -> LazyColumn(
                    Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = forest), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("خوش آمدید", color = Color(0xFFD4E3DA), style = MaterialTheme.typography.labelLarge)
                                Text("ثبت منظم، گزارش دقیق", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("اطلاعات روزانه را ثبت کنید تا سابقه کارکرد شما برای سال‌های آینده محفوظ بماند.", color = Color(0xFFE3ECE6), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MetricPill("روزهای ثبت‌شده", "${rows.count { it.dateText.isNotBlank() || it.report.isNotBlank() || it.advance.isNotBlank() || it.overtime.isNotBlank() }} / ۶")
                                    MetricPill("هفته", week.value)
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("برنامه هفتگی", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { showArchive = !showArchive; if (showArchive) scope.launch { weeks = dao.getWeeks() } }) { Text(if (showArchive) "بازگشت" else "آرشیو هفته‌ها") }
                                }
                                if (!showArchive) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { weekOffset--; week.value = jalaliWeekKey(weekOffset) }) { Text("هفته قبل") }
                                        Text("شروع هفته: " + jalaliDate(jalaliWeekStart(weekOffset)), color = muted, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { weekOffset++; week.value = jalaliWeekKey(weekOffset) }) { Text("هفته بعد") }
                                    }
                                }
                                if (showArchive) {
                                    if (weeks.isEmpty()) Text("هنوز هفته‌ای ذخیره نشده است.")
                                    weeks.forEach { w -> TextButton(onClick = { week.value = w; showArchive = false }) { Text(w) } }
                                } else {
                                    Text("برای هر روز، تاریخ شمسی، مساعده، اضافه‌کاری و گزارش را ثبت کنید.", style = Materi                    if (!showArchive) {
                        item {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("جدول ثبت کارکرد هفتگی", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("برای دیدن همه ستون‌ها، جدول را افقی جابه‌جا کنید.", color = muted, style = MaterialTheme.typography.bodySmall)
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    ) {
                                        Row(
                                            Modifier.width(810.dp).background(forest, RoundedCornerShape(10.dp)).padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            listOf("تاریخ", "روز", "اضافه‌کار", "مساعده", "گزارشات").forEachIndexed { index, title ->
                                                Text(
                                                    title, color = Color.White, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.width(listOf(130, 100, 170, 170, 240)[index]).padding(horizontal = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        items(rows, key = { it.dayIndex }) { entry ->
                            var date by remember(entry.weekKey, entry.dayIndex, entry.dateText) { mutableStateOf(entry.dateText.ifBlank { jalaliDateForDay(entry.weekKey, entry.dayIndex) }) }
                            var ot by remember(entry.weekKey, entry.dayIndex, entry.overtime) { mutableStateOf(entry.overtime) }
                            var adv by remember(entry.weekKey, entry.dayIndex, entry.advance) { mutableStateOf(entry.advance) }
                            var report by remember(entry.weekKey, entry.dayIndex, entry.report) { mutableStateOf(entry.report) }
                            var confirmDelete by remember { mutableStateOf(false) }
                            Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                            Row(Modifier.width(810.dp), verticalAlignment = Alignment.Top) {
                                                OutlinedTextField(
                                                    date, { date = it }, label = { Text("تاریخ شمسی") },
                                                    placeholder = { Text("۱۴۰۵/۰۸/۲۲") }, modifier = Modifier.width(130.dp),
                                                    singleLine = true, shape = RoundedCornerShape(10.dp)
                                                )
                                                Column(Modifier.width(100.dp).padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Surface(color = leaf, shape = RoundedCornerShape(10.dp)) {
                                                        Text(karbanDays[entry.dayIndex], color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                    Text(if (entry.dateText.isNotBlank() || entry.report.isNotBlank() || entry.advance.isNotBlank() || entry.overtime.isNotBlank()) "ثبت‌شده" else "جدید", color = muted, style = MaterialTheme.typography.labelSmall)
                                                }
                                                OutlinedTextField(
                                                    ot, { ot = it }, label = { Text("اضافه‌کار") },
                                                    modifier = Modifier.width(170.dp), minLines = 2, shape = RoundedCornerShape(10.dp)
                                                )
                                                OutlinedTextField(
                                                    adv, { adv = it }, label = { Text("مساعده") },
                                                    modifier = Modifier.width(170.dp), minLines = 2, shape = RoundedCornerShape(10.dp)
                                                )
                                                OutlinedTextField(
                                                    report, { report = it }, label = { Text("گزارشات روز") },
                                                    modifier = Modifier.width(240.dp), minLines = 2, shape = RoundedCornerShape(10.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (entry.dayIndex > 0) OutlinedButton(onClick = {
                                            val prev = rows.firstOrNull { it.dayIndex == entry.dayIndex - 1 }
                                            if (prev != null) { adv = prev.advance; ot = prev.overtime; report = prev.report; notice = "اطلاعات روز قبل کپی شد؛ تاریخ را جداگانه اصلاح کنید." }
                                        }, modifier = Modifier.weight(1f)) { Text("کپی روز قبل") }
                                        Button(onClick = {
                                            scope.launch {
                                                if (date.isBlank() && (adv.isNotBlank() || ot.isNotBlank() || report.isNotBlank())) {
                                                    notice = "برای ذخیره این روز، ابتدا تاریخ شمسی را وارد کنید."
                                                } else {
                                                    val duplicate = if (date.isNotBlank()) dao.countDuplicate(week.value, entry.dayIndex, date, adv, ot, report) else 0
                                                    if (duplicate > 0) notice = "اطلاعات مشابه برای روز دیگری ثبت شده است."
                                                    else {
                                                        dao.saveDay(DailyEntry(week.value, entry.dayIndex, date, ot, adv, report))
                                                        notice = "اطلاعات ${karbanDays[entry.dayIndex]} ذخیره شد."
                                                        refresh()
                                                    }
                                                }
                                            }
                                        }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("ذخیره ردیف") }
                                    }
                                    if (entry.dateText.isNotBlank() || entry.report.isNotBlank() || entry.advance.isNotBlank() || entry.overtime.isNotBlank()) {
                                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) { Text("انتقال به بازیافت", color = MaterialTheme.colorScheme.error) }
                                    }
                                    if (confirmDelete) AlertDialog(
                                        onDismissRequest = { confirmDelete = false },
                                        title = { Text("انتقال اطلاعات به بازیافت؟") },
                                        text = { Text("این روز حذف دائمی نمی‌شود و از بخش بازیافت قابل بازگردانی است.") },
                                        confirmButton = { TextButton(onClick = {
                                            scope.launch { dao.moveToTrash(entry.copy(dateText = date, overtime = ot, advance = adv, report = report), System.currentTimeMillis()); confirmDelete = false; notice = "اطلاعات به بازیافت منتقل شد."; refresh() }
                                        }) { Text("انتقال") } },
                                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } }
                                    )
                                }
                            }
                        }
                    }
onfirmDelete = false }) { Text("انصراف") } }
                                )
                            }
                        }
                    }
                    if (notice.isNotBlank()) item { Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
                }
                "گزارش‌ها" -> {
                    val all = remember { mutableStateOf(emptyList<DailyEntry>()) }
                    LaunchedEffect(page) { all.value = dao.getAll() }
                    val entries = all.value
                    val monthKey = monthFilter.trim().replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
                    val filteredEntries = if (monthKey.isBlank()) entries else entries.filter { it.dateText.replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9').startsWith(monthKey) }
                    val savedDays = filteredEntries.count { it.dateText.isNotBlank() || it.report.isNotBlank() || it.advance.isNotBlank() || it.overtime.isNotBlank() }
                    val advances = filteredEntries.sumOf { it.advance.lines().sumOf(::amountValue) }
                    val overtimeCount = filteredEntries.sumOf { it.overtime.lines().count { line -> line.isNotBlank() } }
                    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { Text("گزارش عملکرد", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("گزارش ماهانه و سوابق ثبت‌شده", color = muted) }
                        item { OutlinedTextField(monthFilter, { monthFilter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("فیلتر ماه شمسی؛ مانند ۱۴۰۵/۰۸") }, placeholder = { Text("خالی = همه ماه‌ها") }, singleLine = true, shape = RoundedCornerShape(14.dp)) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SummaryCard("روزهای دارای اطلاعات", "$savedDays", Modifier.weight(1f))
                            SummaryCard("نوبت‌های اضافه‌کاری", "$overtimeCount", Modifier.weight(1f))
                        } }
                        item { SummaryCard("جمع مبالغ مساعده قابل محاسبه", String.format(Locale.US, "%,.0f", advances), Modifier.fillMaxWidth(), "مقادیر متنیِ غیرعددی در جمع لحاظ نمی‌شوند.") }
                        item { Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("گزارش مساعده", fontWeight = FontWeight.Bold)
                                filteredEntries.filter { it.advance.isNotBlank() }.forEach { e ->
                                    Text("${e.dateText.ifBlank { e.weekKey }} • ${karbanDays.getOrElse(e.dayIndex) { "" }}: ${e.advance.replace("\n", "، ")}")
                                }
                                if (filteredEntries.none { it.advance.isNotBlank() }) Text("هنوز مساعده‌ای ثبت نشده است.", color = muted)
                            }
                        } }
                        item { Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("گزارش اضافه‌کاری", fontWeight = FontWeight.Bold)
                                filteredEntries.filter { it.overtime.isNotBlank() }.forEach { e ->
                                    Text("${e.dateText.ifBlank { e.weekKey }} • ${karbanDays.getOrElse(e.dayIndex) { "" }}: ${e.overtime.replace("\n", "، ")}")
                                }
                                if (filteredEntries.none { it.overtime.isNotBlank() }) Text("هنوز اضافه‌کاری ثبت نشده است.", color = muted)
                            }
                        } }
                        item { Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("دریافت خروجی", fontWeight = FontWeight.Bold)
                                Button(onClick = { pdfLauncher.launch("KARBAN-Report.pdf") }, modifier = Modifier.fillMaxWidth()) { Text("دریافت گزارش PDF") }
                                OutlinedButton(onClick = { csvLauncher.launch("KARBAN-Report.csv") }, modifier = Modifier.fillMaxWidth()) { Text("خروجی CSV برای Excel") }
                            }
                        } }
                    }
                }
                "جست‌وجو" -> Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                    Text("جست‌وجوی سوابق", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(searchText, { searchText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("تاریخ، گزارش، مساعده یا اضافه‌کاری") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.height(10.dp))
                    if (searchText.isBlank()) Text("عبارت موردنظر را وارد کنید.", color = muted)
                    else if (searchResults.isEmpty()) Text("نتیجه‌ای پیدا نشد.", color = muted)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchResults) { e ->
                            Card(shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("${e.dateText} • ${karbanDays.getOrElse(e.dayIndex) { "" }}", fontWeight = FontWeight.Bold)
                                    if (e.advance.isNotBlank()) Text("مساعده: ${e.advance.replace("\n", "، ")}")
                                    if (e.overtime.isNotBlank()) Text("اضافه‌کاری: ${e.overtime.replace("\n", "، ")}")
                                    if (e.report.isNotBlank()) Text(e.report)
                                    Text("هفته ${e.weekKey}", color = muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("بازیافت اطلاعات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("اطلاعات حذف‌شده را می‌توانید به سوابق برگردانید.", color = muted) }
                    if (trash.isEmpty()) item { Card { Text("سطل بازیافت خالی است.", modifier = Modifier.padding(18.dp), color = muted) } }
                    items(trash, key = { "${it.weekKey}-${it.dayIndex}-${it.deletedAt}" }) { item ->
                        Card(shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${item.dateText.ifBlank { item.weekKey }} • ${karbanDays.getOrElse(item.dayIndex) { "" }}", fontWeight = FontWeight.Bold)
                                Text(item.report.ifBlank { "بدون گزارش روزانه" }, color = muted)
                                Button(onClick = { scope.launch { dao.restore(item); notice = "اطلاعات بازیابی شد."; refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("بازیابی اطلاعات") }
                            }
                        }
                    }
                }
            }
        }
        if (about) AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("درباره KARBAN", fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سامانه ثبت و مدیریت کارکرد")
                Text("نسخه 1.2")
                Text("طراحی و توسعه: محمد جواد احمدی")
                HorizontalDivider()
                Text("راه ارتباطی")
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:09017975131"))) }, modifier = Modifier.fillMaxWidth()) { Text("۰۹۰۱۷۹۷۵۱۳۱") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:09904711802"))) }, modifier = Modifier.fillMaxWidth()) { Text("۰۹۹۰۴۷۱۱۸۰۲") }
            } },
            confirmButton = { TextButton(onClick = { about = false }) { Text("بستن") } }
        )
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Surface(color = Color(0xFF31594C), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Text(label, color = Color(0xFFD4E3DA), style = MaterialTheme.typography.labelSmall)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier, note: String = "") {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (note.isNotBlank()) Text(note, color = muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
