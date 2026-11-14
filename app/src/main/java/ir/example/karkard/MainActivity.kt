package ir.example.karkard

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private val Accent = Color(0xFF31C47B)
private val AccentBright = Color(0xFF62DFA0)
private val AppBg = Color(0xFF07110E)
private val CardBg = Color(0xFF101C17)
private val CardAlt = Color(0xFF17251E)
private val Muted = Color(0xFF91A399)
private val MainText = Color(0xFFF1F7F3)

data class WorkItem(val title: String, val value: Double)
data class WorkRecord(val id: String, val date: String, val advances: List<WorkItem>, val overtimes: List<WorkItem>, val report: String)

class MainActivity : ComponentActivity() {
    private lateinit var dataFile: AtomicFile
    private val keyAlias = "karban_records_key_v1"
    private val storagePrefix = "KARBAN1:"
    private var storageHealthy = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataFile = AtomicFile(File(filesDir, "karban_records.json"))
        val initial = readRecords()
        val preferences = getSharedPreferences("karban_settings", MODE_PRIVATE)
        setContent { KarbanApp(initial, storageHealthy, preferences.getBoolean("light_theme", false), { value -> preferences.edit().putBoolean("light_theme", value).apply() }, ::writeRecords, ::restoreRecords, ::exportBackup, ::readBackup) }
    }

    private fun encryptionKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 28) { "Encrypted data is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun parseRecords(array: JSONArray): List<WorkRecord> {
        require(array.length() <= 10000)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            fun parseItems(key: String): List<WorkItem> {
                val items = obj.optJSONArray(key) ?: JSONArray()
                require(items.length() <= 100)
                return (0 until items.length()).map { n ->
                    val item = items.getJSONObject(n)
                    val value = item.optDouble("value")
                    require(value.isFinite() && value >= 0)
                    WorkItem(item.optString("title").take(200), value)
                }
            }
            val id = obj.optString("id")
            val date = obj.optString("date")
            require(id.isNotBlank() && id.length <= 100)
            require(runCatching { LocalDate.parse(date) }.isSuccess)
            WorkRecord(id, date, parseItems("advances"), parseItems("overtimes"), obj.optString("report").take(5000))
        }
    }

    private fun encodeRecords(records: List<WorkRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            fun items(list: List<WorkItem>) = JSONArray().also { out ->
                list.forEach { out.put(JSONObject().put("title", it.title).put("value", it.value)) }
            }
            array.put(JSONObject().put("id", record.id).put("date", record.date)
                .put("advances", items(record.advances)).put("overtimes", items(record.overtimes))
                .put("report", record.report))
        }
        return array.toString()
    }

    private fun readRecords(): List<WorkRecord> {
        return try {
            val raw = dataFile.openRead().bufferedReader().use { it.readText() }
            val json = if (raw.startsWith(storagePrefix)) decrypt(raw.removePrefix(storagePrefix)) else raw
            parseRecords(JSONArray(json))
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (error: Exception) {
            storageHealthy = false
            android.util.Log.e("KARBAN", "Stored data could not be read; writes disabled to protect it.", error)
            emptyList()
        }
    }

    private fun writeRecords(records: List<WorkRecord>): Boolean {
        if (!storageHealthy) return false
        val stream = try { dataFile.startWrite() } catch (_: Exception) { return false }
        return try {
            stream.write((storagePrefix + encrypt(encodeRecords(records))).toByteArray(Charsets.UTF_8))
            dataFile.finishWrite(stream)
            true
        } catch (error: Exception) {
            dataFile.failWrite(stream)
            android.util.Log.e("KARBAN", "Encrypted save failed", error)
            false
        }
    }

    private fun restoreRecords(records: List<WorkRecord>): Boolean {
        val previousHealth = storageHealthy
        storageHealthy = true
        val restored = writeRecords(records)
        if (!restored) storageHealthy = previousHealth
        return restored
    }

    private fun exportBackup(uri: Uri, records: List<WorkRecord>) {
        val payload = JSONObject().put("app", "KARBAN").put("formatVersion", 1)
            .put("records", JSONArray(encodeRecords(records))).toString(2)
        contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            ?: error("Unable to open backup destination")
    }

    private fun readBackup(uri: Uri): List<WorkRecord> {
        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to read backup")
        require(raw.length <= 10_000_000)
        val root = JSONObject(raw)
        require(root.optString("app") == "KARBAN" && root.optInt("formatVersion") == 1)
        return parseRecords(root.getJSONArray("records"))
    }
}

@Composable
private fun KarbanApp(
    initialRecords: List<WorkRecord>,
    storageHealthy: Boolean,
    initialLightTheme: Boolean,
    saveTheme: (Boolean) -> Unit,
    saveRecords: (List<WorkRecord>) -> Boolean,
    restoreRecords: (List<WorkRecord>) -> Boolean,
    exportBackup: (Uri, List<WorkRecord>) -> Unit,
    readBackup: (Uri) -> List<WorkRecord>
) {
    var records by remember { mutableStateOf(initialRecords) }
    var page by remember { mutableStateOf("home") }
    var isLight by remember { mutableStateOf(initialLightTheme) }
    var selectedDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var advances by remember { mutableStateOf(listOf(WorkItem("", 0.0))) }
    var overtimes by remember { mutableStateOf(listOf(WorkItem("", 0.0))) }
    var report by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf(if (storageHealthy) "" else "خواندن اطلاعات قبلی ممکن نشد. برای محافظت از سوابق، ذخیره‌سازی غیرفعال شده است. برنامه را حذف نکنید و فایل پشتیبان سالم را بازیابی کنید.") }
    var weekOffset by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<WorkRecord?>(null) }
    var pendingRestore by remember { mutableStateOf<List<WorkRecord>?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var calendarYear by remember { mutableIntStateOf(jalaliParts(LocalDate.parse(selectedDate)).first) }
    var calendarMonth by remember { mutableIntStateOf(jalaliParts(LocalDate.parse(selectedDate)).second) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { exportBackup(uri, records); notice = "فایل پشتیبان ذخیره شد." }
            .onFailure { notice = "ذخیره فایل پشتیبان ناموفق بود." }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val restored = runCatching { readBackup(uri) }.getOrElse { notice = "فایل پشتیبان معتبر نیست."; null }
            if (restored != null) pendingRestore = restored
        }
    }

    val background = if (isLight) Color(0xFFF1F5F2) else AppBg
    val surface = if (isLight) Color.White else CardBg
    val alternate = if (isLight) Color(0xFFEAF1EC) else CardAlt
    val foreground = if (isLight) Color(0xFF14231A) else MainText
    val secondary = if (isLight) Color(0xFF65766B) else Muted

    fun total(items: List<WorkItem>) = items.sumOf { it.value }
    fun persist(next: List<WorkRecord>) {
        if (saveRecords(next)) records = next else notice = "ذخیره‌سازی تأیید نشد؛ اطلاعات قبلی حفظ شدند."
    }
    fun beginNew(date: String = LocalDate.now().toString()) {
        selectedDate = date
        editingId = null
        advances = listOf(WorkItem("", 0.0))
        overtimes = listOf(WorkItem("", 0.0))
        report = ""
        page = "add"
    }
    fun edit(record: WorkRecord) {
        selectedDate = record.date
        editingId = record.id
        advances = record.advances.ifEmpty { listOf(WorkItem("", 0.0)) }
        overtimes = record.overtimes.ifEmpty { listOf(WorkItem("", 0.0)) }
        report = record.report
        page = "add"
    }

    MaterialTheme(colorScheme = if (isLight) lightColorScheme(primary = Accent, background = background, surface = surface, onSurface = foreground) else darkColorScheme(primary = AccentBright, background = background, surface = surface, onSurface = foreground)) {
        Scaffold(
            containerColor = background,
            bottomBar = {
                NavigationBar(containerColor = surface) {
                    listOf("home" to "خانه", "week" to "هفتگی", "add" to "ثبت", "records" to "سوابق", "reports" to "گزارش").forEach { (key, label) ->
                        NavigationBarItem(
                            selected = page == key,
                            onClick = { if (key == "add") beginNew() else page = key },
                            icon = { Text(when (key) { "home" -> "⌂"; "week" -> "▦"; "add" -> "＋"; "records" -> "≡"; else -> "▤" }, fontSize = 19.sp) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 17.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).background(Accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Text("K", color = AppBg, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("KARBAN", color = foreground, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("مدیریت هوشمند کارکرد", color = secondary, fontSize = 11.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("درباره", color = AccentBright, modifier = Modifier.clickable { page = "about" }.padding(8.dp))
                        Text(if (isLight) "☾" else "☀", color = foreground, fontSize = 20.sp, modifier = Modifier.clickable { isLight = !isLight; saveTheme(isLight) }.padding(8.dp))
                    }
                }

                when (page) {
                    "home" -> {
                        Heading("خلاصه کارکرد", "نگاهی به وضعیت فعالیت‌های شما", foreground, secondary)
                        Panel(surface) {
                            Text("مجموع مساعده‌ها", color = secondary, fontSize = 12.sp)
                            Text(total(records.flatMap { it.advances }).toLong().toString(), color = AccentBright, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                            Text("مجموع مبالغ ثبت‌شده", color = secondary, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Metric("روزهای ثبت‌شده", records.size.toString(), surface, secondary, Modifier.weight(1f))
                            Metric("روزهای اضافه‌کاری", records.count { total(it.overtimes) > 0 }.toString(), surface, secondary, Modifier.weight(1f))
                        }
                        Heading("آخرین فعالیت‌ها", "", foreground, secondary)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(records.sortedByDescending { it.date }.take(5), key = { it.id }) { record ->
                                RecordCard(record, surface, foreground, secondary, false, { edit(record) }, { deleteTarget = record })
                            }
                        }
                    }
                    "week" -> {
                        Heading("کارکرد هفتگی", "روزهای کاری خود را بررسی و ثبت کنید.", foreground, secondary)
                        val start = weekStart(LocalDate.now()).plusWeeks(weekOffset.toLong())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { weekOffset-- }) { Text("‹ هفته قبل") }
                            TextButton(onClick = { weekOffset++ }) { Text("هفته بعد ›") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (0..6).forEach { index ->
                                val day = start.plusDays(index.toLong())
                                Column(
                                    Modifier.weight(1f).background(if (selectedDate == day.toString()) Accent else alternate, RoundedCornerShape(10.dp))
                                        .clickable { selectedDate = day.toString() }.padding(vertical = 11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(listOf("ش", "ی", "د", "س", "چ", "پ", "ج")[index], color = if (selectedDate == day.toString()) AppBg else secondary, fontSize = 10.sp)
                                    Text(day.dayOfMonth.toString(), color = if (selectedDate == day.toString()) AppBg else foreground, fontWeight = FontWeight.Bold)
                                    if (records.any { it.date == day.toString() }) Box(Modifier.padding(top = 4.dp).size(4.dp).background(if (selectedDate == day.toString()) AppBg else Accent, RoundedCornerShape(50)))
                                }
                            }
                        }
                        Text("تاریخ انتخاب‌شده: ${persianDate(selectedDate)}", color = AccentBright, modifier = Modifier.padding(vertical = 13.dp))
                        records.filter { it.date == selectedDate }.forEach { record ->
                            RecordCard(record, surface, foreground, secondary, true, { edit(record) }, { deleteTarget = record })
                        }
                        PrimaryButton("＋ ثبت اطلاعات این روز") { beginNew(selectedDate) }
                    }
                    "add" -> {
                        Heading(if (editingId == null) "ثبت کارکرد" else "ویرایش کارکرد", "اطلاعات روزانه را وارد کنید.", foreground, secondary)
                        Panel(surface) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("تاریخ ثبت", color = secondary, fontSize = 11.sp)
                                    Text(persianDate(selectedDate), color = foreground, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = {
                                    val parts = jalaliParts(LocalDate.parse(selectedDate))
                                    calendarYear = maxOf(1405, parts.first)
                                    calendarMonth = if (parts.first < 1405) 1 else parts.second
                                    showCalendar = true
                                }) { Text("انتخاب تاریخ شمسی") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("مساعده‌ها", color = foreground, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { advances = advances + WorkItem("", 0.0) }) { Text("+ افزودن") }
                            }
                            ItemEditor(advances, "عنوان مساعده", "مبلغ", alternate) { advances = it }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("اضافه‌کاری", color = foreground, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { overtimes = overtimes + WorkItem("", 0.0) }) { Text("+ افزودن") }
                            }
                            ItemEditor(overtimes, "عنوان نوبت", "ساعت", alternate) { overtimes = it }
                            Text("گزارش روزانه", color = secondary, fontSize = 12.sp)
                            OutlinedTextField(report, { report = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("فعالیت‌های امروز را بنویسید...") })
                            TextButton(onClick = {
                                val previous = LocalDate.parse(selectedDate).minusDays(1).toString()
                                val source = records.firstOrNull { it.date == previous }
                                if (source == null) notice = "برای روز قبل اطلاعاتی ثبت نشده است."
                                else {
                                    advances = source.advances.ifEmpty { listOf(WorkItem("", 0.0)) }
                                    overtimes = source.overtimes.ifEmpty { listOf(WorkItem("", 0.0)) }
                                    report = source.report
                                    notice = "اطلاعات روز قبل کپی شد."
                                }
                            }) { Text("کپی اطلاعات روز قبل") }
                            PrimaryButton("ذخیره کارکرد") {
                                val record = WorkRecord(
                                    editingId ?: System.currentTimeMillis().toString(), selectedDate,
                                    advances.filter { it.title.isNotBlank() || it.value > 0 },
                                    overtimes.filter { it.title.isNotBlank() || it.value > 0 }, report
                                )
                                val next = if (editingId == null) records + record else records.map { if (it.id == editingId) record else it }
                                if (saveRecords(next)) {
                                    records = next; editingId = null; advances = listOf(WorkItem("", 0.0))
                                    overtimes = listOf(WorkItem("", 0.0)); report = ""; page = "home"; notice = "اطلاعات ذخیره شد."
                                } else notice = "ذخیره‌سازی ناموفق بود؛ اطلاعات قبلی حفظ شدند."
                            }
                        }
                    }
                    "records" -> {
                        Heading("سوابق کارکرد", "جست‌وجو، ویرایش و حذف اطلاعات", foreground, secondary)
                        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("جست‌وجو در تاریخ و گزارش...") })
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(records.sortedByDescending { it.date }.filter { persianDate(it.date).contains(search) || it.date.contains(search) || it.report.contains(search) }, key = { it.id }) { record ->
                                RecordCard(record, surface, foreground, secondary, true, { edit(record) }, { deleteTarget = record })
                            }
                        }
                    }
                    "reports" -> {
                        Heading("گزارش‌ها", "خلاصه عملکرد و اطلاعات مالی", foreground, secondary)
                        Metric("روزهای ثبت‌شده", records.size.toString(), surface, secondary, Modifier.fillMaxWidth())
                        Metric("روزهای دارای اضافه‌کاری", records.count { total(it.overtimes) > 0 }.toString(), surface, secondary, Modifier.fillMaxWidth())
                        Metric("مجموع مساعده‌ها", total(records.flatMap { it.advances }).toLong().toString(), surface, secondary, Modifier.fillMaxWidth())
                        Metric("مجموع ساعت اضافه‌کاری", total(records.flatMap { it.overtimes }).toString(), surface, secondary, Modifier.fillMaxWidth())
                    }
                    "about" -> {
                        Heading("درباره برنامه", "KARBAN · نسخه 2.2", foreground, secondary)
                        Panel(surface) {
                            Text("KARBAN", color = AccentBright, fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Text("مدیریت هوشمند کارکرد", color = secondary)
                            Text("ابزاری برای ثبت و مدیریت کارکرد روزانه، مساعده، اضافه‌کاری و گزارش‌های کاری.", color = foreground)
                            Divider(color = secondary.copy(alpha = 0.3f))
                            Text("سازنده: محمد جواد احمدی", color = foreground)
                            Text("ذخیره‌سازی داخلی با AES-GCM و Android Keystore محافظت می‌شود.", color = secondary, fontSize = 12.sp)
                            Text("فایل پشتیبان JSON رمزنگاری نشده است؛ آن را در محل امن نگهداری کنید.", color = secondary, fontSize = 12.sp)
                            PrimaryButton("دریافت فایل پشتیبان") { exportLauncher.launch("KARBAN-backup.json") }
                            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) { Text("بازیابی از فایل") }
                        }
                    }
                }
            }
        }
    }
    if (showCalendar) {
        val monthNames = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val firstDay = jalaliToGregorian(calendarYear, calendarMonth, 1)
        val monthLength = jalaliMonthLength(calendarYear, calendarMonth)
        val offset = (firstDay.dayOfWeek.value + 1) % 7
        AlertDialog(
            onDismissRequest = { showCalendar = false },
            title = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (calendarMonth == 1) { if (calendarYear > 1405) { calendarYear--; calendarMonth = 12 } }
                        else calendarMonth--
                    }, enabled = calendarYear > 1405 || calendarMonth > 1) { Text("‹") }
                    Text("${monthNames[calendarMonth - 1]} $calendarYear", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        if (calendarMonth == 12) { calendarYear++; calendarMonth = 1 } else calendarMonth++
                    }) { Text("›") }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEach { Text(it, Modifier.weight(1f), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                    repeat(6) { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(7) { column ->
                                val dayNumber = week * 7 + column - offset + 1
                                if (dayNumber in 1..monthLength) {
                                    val chosen = jalaliToGregorian(calendarYear, calendarMonth, dayNumber).toString() == selectedDate
                                    TextButton(
                                        onClick = {
                                            selectedDate = jalaliToGregorian(calendarYear, calendarMonth, dayNumber).toString()
                                            showCalendar = false
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = if (chosen) AccentBright else foreground)
                                    ) { Text(dayNumber.toString(), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal) }
                                } else Spacer(Modifier.weight(1f).height(40.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCalendar = false }) { Text("بستن") } }
        )
    }
    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("بازیابی اطلاعات") },
            text = { Text("سوابق فعلی با اطلاعات فایل پشتیبان جایگزین می‌شوند. قبل از ادامه مطمئن شوید که فایل انتخابی متعلق به کاربان است.") },
            confirmButton = {
                TextButton(onClick = {
                    val restored = pendingRestore
                    if (restored != null) {
                        if (restoreRecords(restored)) { records = restored; notice = "اطلاعات با موفقیت بازیابی شد." }
                        else notice = "بازیابی ناموفق بود؛ اطلاعات فعلی حفظ شدند."
                    }
                    pendingRestore = null
                }) { Text("جایگزینی و بازیابی") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("انصراف") } }
        )
    }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف کارکرد") },
            text = { Text("آیا از حذف اطلاعات این روز مطمئن هستید؟") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget
                    if (target != null) persist(records.filterNot { it.id == target.id })
                    deleteTarget = null
                }) { Text("حذف", color = Color(0xFFED6262)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("انصراف") } }
        )
    }
    if (notice.isNotBlank()) {
        AlertDialog(onDismissRequest = { notice = "" }, confirmButton = { TextButton(onClick = { notice = "" }) { Text("متوجه شدم") } }, text = { Text(notice) })
    }
}

private fun weekStart(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value + 1) % 7).toLong())

private fun jalaliParts(date: LocalDate): Triple<Int, Int, Int> {
    if (Build.VERSION.SDK_INT >= 24) {
        val calendar = android.icu.util.PersianCalendar()
        calendar.timeInMillis = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Triple(calendar.get(android.icu.util.Calendar.YEAR), calendar.get(android.icu.util.Calendar.MONTH) + 1, calendar.get(android.icu.util.Calendar.DAY_OF_MONTH))
    }
    val gy = date.year; val gm = date.monthValue; val gd = date.dayOfMonth
    val gdm = intArrayOf(0,31,if (java.time.Year.isLeap(gy.toLong())) 29 else 28,31,30,31,30,31,31,30,31,30,31)
    val gy2 = gy - 1600; val gm2 = gm - 1; val gd2 = gd - 1
    var gDayNo = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
    for (i in 0 until gm2) gDayNo += gdm[i + 1]
    gDayNo += gd2
    var jDayNo = gDayNo - 79
    val jNp = jDayNo / 12053; jDayNo %= 12053
    var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
    jDayNo %= 1461
    if (jDayNo >= 366) { jy += (jDayNo - 1) / 365; jDayNo = (jDayNo - 1) % 365 }
    val jm = if (jDayNo < 186) 1 + jDayNo / 31 else 7 + (jDayNo - 186) / 30
    val jd = 1 + if (jDayNo < 186) jDayNo % 31 else (jDayNo - 186) % 30
    return Triple(jy, jm, jd)
}

private fun jalaliToGregorian(year: Int, month: Int, day: Int): LocalDate {
    require(year >= 1405 && month in 1..12 && day in 1..31)
    val jy = year + 1595
    var days = -355668 + 365 * jy + (jy / 33) * 8 + ((jy % 33 + 3) / 4) + day
    days += if (month < 7) (month - 1) * 31 else (month - 7) * 30 + 186
    var gy = 400 * (days / 146097)
    days %= 146097
    if (days > 36524) {
        days--
        gy += 100 * (days / 36524)
        days %= 36524
        if (days >= 365) days++
    }
    gy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        gy += (days - 1) / 365
        days = (days - 1) % 365
    }
    var gd = days + 1
    val leap = gy % 4 == 0 && (gy % 100 != 0 || gy % 400 == 0)
    val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var gm = 0
    while (gm < 12 && gd > monthDays[gm]) { gd -= monthDays[gm]; gm++ }
    return LocalDate.of(gy, gm + 1, gd)
}

private fun jalaliMonthLength(year: Int, month: Int): Int {
    val start = jalaliToGregorian(year, month, 1)
    val next = if (month == 12) jalaliToGregorian(year + 1, 1, 1) else jalaliToGregorian(year, month + 1, 1)
    return java.time.temporal.ChronoUnit.DAYS.between(start, next).toInt()
}

private fun persianDate(iso: String): String = runCatching {
    val parts = jalaliParts(LocalDate.parse(iso))
    "${parts.first}/${parts.second.toString().padStart(2, '0')}/${parts.third.toString().padStart(2, '0')}"
}.getOrDefault(iso)

@Composable
private fun Heading(title: String, subtitle: String, color: Color, muted: Color) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, color = color, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
@Composable
private fun Panel(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(color, RoundedCornerShape(19.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable
private fun Metric(label: String, value: String, surface: Color, muted: Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 9.dp).background(surface, RoundedCornerShape(17.dp)).padding(15.dp)) {
        Text(label, color = muted, fontSize = 12.sp)
        Text(value, color = AccentBright, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun PrimaryButton(label: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AppBg), shape = RoundedCornerShape(12.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun ItemEditor(items: List<WorkItem>, titleHint: String, valueHint: String, background: Color, onChange: (List<WorkItem>) -> Unit) {
    items.forEachIndexed { index, item ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(item.title, { value -> onChange(items.toMutableList().also { it[index] = item.copy(title = value) }) },
                modifier = Modifier.weight(1.2f), placeholder = { Text(titleHint, fontSize = 10.sp) }, singleLine = true)
            OutlinedTextField(if (item.value == 0.0) "" else item.value.toString(), { value ->
                onChange(items.toMutableList().also { it[index] = item.copy(value = value.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0) })
            }, modifier = Modifier.weight(1f), placeholder = { Text(valueHint, fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Text("×", color = Color(0xFFED6262), fontSize = 22.sp, modifier = Modifier.clickable {
                onChange(items.toMutableList().also { it.removeAt(index) }.ifEmpty { listOf(WorkItem("", 0.0)) })
            }.padding(6.dp))
        }
    }
}
@Composable
private fun RecordCard(record: WorkRecord, surface: Color, foreground: Color, muted: Color, actions: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Panel(surface) {
        Text(persianDate(record.date), color = AccentBright, fontWeight = FontWeight.Bold)
        Text("مجموع مساعده: ${record.advances.sumOf { it.value }.toLong()}", color = muted, fontSize = 12.sp)
        Text("مجموع اضافه‌کاری: ${record.overtimes.sumOf { it.value }} ساعت", color = muted, fontSize = 12.sp)
        if (record.report.isNotBlank()) Text("گزارش: ${record.report}", color = foreground, fontSize = 12.sp)
        if (actions) Row {
            TextButton(onClick = onEdit) { Text("ویرایش") }
            TextButton(onClick = onDelete) { Text("حذف", color = Color(0xFFED6262)) }
        }
    }
}
