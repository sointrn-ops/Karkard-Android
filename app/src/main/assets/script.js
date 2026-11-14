const $ = id => document.getElementById(id);

const MIN_JALALI_YEAR = 1405;

// Main records are mirrored to Android's private app storage.
// localStorage remains as a migration/fallback copy and stores UI preferences.
function readLocalRecords() {
    try {
        const value = JSON.parse(localStorage.getItem("karbanRecords") || "[]");
        return Array.isArray(value) ? value : [];
    } catch {
        return [];
    }
}

function readNativeRecords() {
    try {
        if (!window.KarbanStorage || typeof window.KarbanStorage.loadRecords !== "function") return null;
        const raw = window.KarbanStorage.loadRecords();
        if (!raw) return null;
        const value = JSON.parse(raw);
        return Array.isArray(value) ? value : null;
    } catch (error) {
        console.error("Native storage read failed:", error);
        return null;
    }
}

const hasNativeStorage = Boolean(window.KarbanStorage &&
    typeof window.KarbanStorage.loadRecords === "function" &&
    typeof window.KarbanStorage.saveRecords === "function");
let records = readLocalRecords();
const nativeRecords = readNativeRecords();

// Android uses app-private native storage; migrate and then remove old plaintext browser data.
if (nativeRecords !== null) {
    records = nativeRecords;
    if (hasNativeStorage) {
        try { localStorage.removeItem("karbanRecords"); } catch (error) {
            console.error("Legacy local data cleanup failed:", error);
        }
    }
} else if (records.length && hasNativeStorage) {
    try {
        if (window.KarbanStorage.saveRecords(JSON.stringify(records))) {
            localStorage.removeItem("karbanRecords");
        }
    } catch (error) {
        console.error("Initial native migration failed:", error);
    }
}

let selectedDate = "";
let editingId = null;
let weekOffset = 0;
let calendarYear = 1405;
let calendarMonth = 1;

const monthNames = [
    "فروردین", "اردیبهشت", "خرداد",
    "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر",
    "دی", "بهمن", "اسفند"
];

const weekNames = [
    "شنبه", "یکشنبه", "دوشنبه",
    "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"
];

function localDate(date) {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, "0");
    const d = String(date.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
}

function todayISO() {
    return localDate(new Date());
}

function getJalaliParts(date) {
    const parts = new Intl.DateTimeFormat("en-US-u-ca-persian-nu-latn", {
        year: "numeric", month: "numeric", day: "numeric"
    }).formatToParts(date);
    const result = {};
    parts.forEach(part => {
        if (part.type !== "literal") result[part.type] = Number(part.value);
    });
    return { year: result.year, month: result.month, day: result.day };
}

function formatDate(iso) {
    if (!iso) return "";
    const parts = getJalaliParts(new Date(iso + "T12:00:00"));
    return `${parts.year}/${String(parts.month).padStart(2, "0")}/${String(parts.day).padStart(2, "0")}`;
}

function getJalaliMonthStart(year, month) {
    const guess = new Date(year + 621, 2, 20 + (month - 1) * 30.5, 12);
    for (let offset = -5; offset <= 5; offset++) {
        const candidate = new Date(guess);
        candidate.setDate(candidate.getDate() + offset);
        const parts = getJalaliParts(candidate);
        if (parts.year === year && parts.month === month && parts.day === 1) return candidate;
    }
    throw new Error("خطا در تبدیل تاریخ شمسی");
}

function getMonthDays(year, month) {
    let nextMonth = month + 1;
    let nextYear = year;
    if (nextMonth > 12) { nextMonth = 1; nextYear++; }
    const first = getJalaliMonthStart(year, month);
    const next = getJalaliMonthStart(nextYear, nextMonth);
    return Math.round((next - first) / 86400000);
}

function setSelectedDate(iso) {
    selectedDate = iso;
    $("date").value = formatDate(iso);
}

function openCalendar() {
    const parts = selectedDate
        ? getJalaliParts(new Date(selectedDate + "T12:00:00"))
        : getJalaliParts(new Date());
    if (parts.year < MIN_JALALI_YEAR) {
        calendarYear = MIN_JALALI_YEAR;
        calendarMonth = 1;
    } else {
        calendarYear = parts.year;
        calendarMonth = parts.month;
    }
    renderCalendar();
    $("calendarModal").hidden = false;
    $("calendarModal").classList.add("show");
}

function closeCalendar() {
    $("calendarModal").hidden = true;
    $("calendarModal").classList.remove("show");
}

function changeMonth(amount) {
    calendarMonth += amount;
    if (calendarMonth > 12) { calendarMonth = 1; calendarYear++; }
    if (calendarMonth < 1) { calendarMonth = 12; calendarYear--; }
    if (calendarYear < MIN_JALALI_YEAR) {
        calendarYear = MIN_JALALI_YEAR;
        calendarMonth = 1;
    }
    renderCalendar();
}

function renderCalendar() {
    const grid = $("calendarGrid");
    grid.innerHTML = "";
    $("calendarTitle").textContent = `${monthNames[calendarMonth - 1]} ${calendarYear}`;
    $("prevMonth").disabled = calendarYear === MIN_JALALI_YEAR && calendarMonth === 1;
    const firstDay = getJalaliMonthStart(calendarYear, calendarMonth);
    const days = getMonthDays(calendarYear, calendarMonth);
    const startIndex = (firstDay.getDay() + 1) % 7;
    ["ش", "ی", "د", "س", "چ", "پ", "ج"].forEach(name => {
        const span = document.createElement("span");
        span.textContent = name;
        span.className = "calendar-weekday";
        grid.appendChild(span);
    });
    for (let i = 0; i < startIndex; i++) {
        const empty = document.createElement("span");
        empty.className = "calendar-empty";
        grid.appendChild(empty);
    }
    const today = todayISO();
    for (let day = 1; day <= days; day++) {
        const date = new Date(firstDay);
        date.setDate(firstDay.getDate() + day - 1);
        const iso = localDate(date);
        const button = document.createElement("button");
        button.type = "button";
        button.className = "calendar-day";
        button.textContent = day;
        if (iso === selectedDate) button.classList.add("selected");
        if (iso === today) button.classList.add("today");
        button.addEventListener("click", () => { setSelectedDate(iso); closeCalendar(); });
        grid.appendChild(button);
    }
}

$("openCalendar").addEventListener("click", openCalendar);
$("prevMonth").addEventListener("click", () => changeMonth(-1));
$("nextMonth").addEventListener("click", () => changeMonth(1));
$("closeCalendar").addEventListener("click", closeCalendar);
$("calendarModal").addEventListener("click", event => {
    if (event.target.id === "calendarModal") closeCalendar();
});

function openPage(pageName) {
    document.querySelectorAll(".page").forEach(page => {
        page.classList.toggle("active", page.id === pageName);
    });
    const activeNavPage = pageName === "about" ? "home" : pageName;
    document.querySelectorAll(".nav-item").forEach(button => {
        button.classList.toggle("active", button.dataset.page === activeNavPage);
    });
    if (pageName === "home") renderHome();
    if (pageName === "week") renderWeek();
    if (pageName === "records") renderRecords();
    if (pageName === "reports") renderReports();
    if (pageName === "add" && !editingId) $("date").value = formatDate(selectedDate);
    window.scrollTo({ top: 0, behavior: "smooth" });
}

document.querySelectorAll("[data-goto]").forEach(button => {
    button.addEventListener("click", () => openPage(button.dataset.goto));
});
document.querySelectorAll(".nav-item").forEach(button => {
    button.addEventListener("click", () => openPage(button.dataset.page));
});
$("aboutBtn").addEventListener("click", () => openPage("about"));
$("exportBackupBtn").addEventListener("click", exportBackup);
$("importBackupBtn").addEventListener("click", importBackup);

function saveRecords() {
    const json = JSON.stringify(records);
    if (hasNativeStorage) {
        try {
            const saved = Boolean(window.KarbanStorage.saveRecords(json));
            if (!saved) console.error("Native private storage did not confirm the save.");
            return saved;
        } catch (error) {
            console.error("Native storage write failed:", error);
            return false;
        }
    }
    try {
        localStorage.setItem("karbanRecords", json);
        return true;
    } catch (error) {
        console.error("Local storage write failed:", error);
        return false;
    }
}

function formatNumber(value) {
    return Number(value || 0).toLocaleString("fa-IR");
}

function createRow(containerId, firstPlaceholder, secondPlaceholder) {
    const container = $(containerId);
    const row = document.createElement("div");
    row.className = "dynamic-row";
    const first = document.createElement("input");
    first.type = "text";
    first.placeholder = firstPlaceholder;
    first.className = "row-title";
    const second = document.createElement("input");
    second.type = "number";
    second.min = "0";
    second.placeholder = secondPlaceholder;
    second.className = "row-value";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "remove-row";
    remove.textContent = "×";
    remove.addEventListener("click", () => row.remove());
    row.append(first, second, remove);
    container.appendChild(row);
}

$("addAdvance").addEventListener("click", () => createRow("advancesList", "عنوان مساعده", "مبلغ"));
$("addOvertime").addEventListener("click", () => createRow("overtimeList", "عنوان نوبت", "ساعت"));

function readRows(containerId) {
    return [...$(containerId).querySelectorAll(".dynamic-row")].map(row => {
        const title = row.querySelector(".row-title").value.trim();
        const value = Number(row.querySelector(".row-value").value) || 0;
        return { title, value };
    }).filter(item => item.title || item.value);
}

function addSavedRows(containerId, items, firstPlaceholder, secondPlaceholder) {
    $(containerId).innerHTML = "";
    (items || []).forEach(item => {
        createRow(containerId, firstPlaceholder, secondPlaceholder);
        const row = $(containerId).lastElementChild;
        row.querySelector(".row-title").value = item.title || "";
        row.querySelector(".row-value").value = item.value || 0;
    });
}

function resetForm() {
    $("recordForm").reset();
    $("advancesList").innerHTML = "";
    $("overtimeList").innerHTML = "";
    editingId = null;
    setSelectedDate(todayISO());
}

function editRecord(record) {
    editingId = record.id;
    setSelectedDate(record.date);
    addSavedRows("advancesList", record.advances, "عنوان مساعده", "مبلغ");
    addSavedRows("overtimeList", record.overtimes, "عنوان نوبت", "ساعت");
    $("report").value = record.report || "";
    openPage("add");
}

$("recordForm").addEventListener("submit", event => {
    event.preventDefault();
    if (!selectedDate) { alert("ابتدا تاریخ را انتخاب کنید."); return; }
    const record = {
        id: editingId || Date.now().toString(),
        date: selectedDate,
        advances: readRows("advancesList"),
        overtimes: readRows("overtimeList"),
        report: $("report").value.trim()
    };
    if (editingId) {
        const index = records.findIndex(item => item.id === editingId);
        if (index !== -1) records[index] = record;
    } else records.push(record);
    const storageOk = saveRecords();
    resetForm();
    renderEverything();
    openPage("home");
    alert(storageOk
        ? "اطلاعات با موفقیت ذخیره شد."
        : "ذخیره‌سازی کامل تأیید نشد. لطفاً از اطلاعات پشتیبان بگیرید و دوباره تلاش کنید.");
});

function advanceTotal(record) {
    return (record.advances || []).reduce((sum, item) => sum + Number(item.value || 0), 0);
}
function overtimeTotal(record) {
    return (record.overtimes || []).reduce((sum, item) => sum + Number(item.value || 0), 0);
}
function totalOvertimeDays() {
    return records.filter(record => overtimeTotal(record) > 0).length;
}
function sortedRecords() {
    return [...records].sort((a, b) => b.date.localeCompare(a.date));
}

function createRecordCard(record, actions = true) {
    const card = document.createElement("div");
    card.className = "record-card";
    const heading = document.createElement("div");
    heading.className = "record-card-heading";
    heading.textContent = formatDate(record.date);
    const details = document.createElement("div");
    details.className = "record-details";
    const advance = document.createElement("p");
    advance.textContent = `مجموع مساعده: ${formatNumber(advanceTotal(record))}`;
    const overtime = document.createElement("p");
    overtime.textContent = `مجموع اضافه‌کاری: ${formatNumber(overtimeTotal(record))} ساعت`;
    details.append(advance, overtime);
    if (record.report) {
        const report = document.createElement("p");
        report.textContent = `گزارش: ${record.report}`;
        details.appendChild(report);
    }
    card.append(heading, details);
    if (actions) {
        const buttons = document.createElement("div");
        buttons.className = "record-actions";
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "secondary small";
        edit.textContent = "ویرایش";
        edit.addEventListener("click", () => editRecord(record));
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "danger small";
        remove.textContent = "حذف";
        remove.addEventListener("click", () => {
            if (confirm("آیا از حذف این کارکرد مطمئن هستید؟")) {
                records = records.filter(item => item.id !== record.id);
                saveRecords();
                renderEverything();
            }
        });
        buttons.append(edit, remove);
        card.appendChild(buttons);
    }
    return card;
}

function renderHome() {
    $("todayLabel").textContent = formatDate(todayISO());
    const totalAdvances = records.reduce((sum, record) => sum + advanceTotal(record), 0);
    $("advanceTotal").textContent = formatNumber(totalAdvances);
    $("dayCount").textContent = formatNumber(records.length);
    $("overtimeCount").textContent = formatNumber(totalOvertimeDays());
    const container = $("recentRecords");
    container.innerHTML = "";
    const recent = sortedRecords().slice(0, 4);
    if (!recent.length) { container.textContent = "هنوز اطلاعاتی ثبت نشده است."; return; }
    recent.forEach(record => container.appendChild(createRecordCard(record, false)));
}

function getWeekDates(offset) {
    const today = new Date();
    const day = today.getDay();
    const difference = day === 6 ? 0 : day + 1;
    const saturday = new Date(today);
    saturday.setHours(12, 0, 0, 0);
    saturday.setDate(today.getDate() - difference + offset * 7);
    return Array.from({ length: 7 }, (_, index) => {
        const date = new Date(saturday);
        date.setDate(saturday.getDate() + index);
        return date;
    });
}

function renderWeek() {
    const dates = getWeekDates(weekOffset);
    const container = $("weekDays");
    container.innerHTML = "";
    const first = getJalaliParts(dates[0]);
    const last = getJalaliParts(dates[6]);
    $("weekTitle").textContent = `${first.day} ${monthNames[first.month - 1]} تا ${last.day} ${monthNames[last.month - 1]}`;
    dates.forEach((date, index) => {
        const iso = localDate(date);
        const parts = getJalaliParts(date);
        const button = document.createElement("button");
        button.type = "button";
        button.className = "week-day";
        if (iso === selectedDate) button.classList.add("selected");
        if (records.some(record => record.date === iso)) button.classList.add("has-record");
        const name = document.createElement("span");
        name.textContent = weekNames[index];
        const number = document.createElement("strong");
        number.textContent = parts.day;
        button.append(name, number);
        button.addEventListener("click", () => { setSelectedDate(iso); renderWeek(); });
        container.appendChild(button);
    });
    renderSelectedDay();
}

function renderSelectedDay() {
    const container = $("selectedDayInfo");
    container.innerHTML = "";
    const dayRecords = records.filter(record => record.date === selectedDate);
    if (!dayRecords.length) { container.textContent = "برای این روز اطلاعاتی ثبت نشده است."; return; }
    dayRecords.forEach(record => container.appendChild(createRecordCard(record)));
}

$("prevWeek").addEventListener("click", () => { weekOffset--; renderWeek(); });
$("nextWeek").addEventListener("click", () => { weekOffset++; renderWeek(); });

$("copyYesterday").addEventListener("click", () => {
    if (!selectedDate) { alert("ابتدا تاریخ را انتخاب کنید."); return; }
    const previous = new Date(selectedDate + "T12:00:00");
    previous.setDate(previous.getDate() - 1);
    const previousISO = localDate(previous);
    const source = records.find(record => record.date === previousISO);
    if (!source) { alert("برای روز قبل اطلاعاتی ثبت نشده است."); return; }
    addSavedRows("advancesList", source.advances, "عنوان مساعده", "مبلغ");
    addSavedRows("overtimeList", source.overtimes, "عنوان نوبت", "ساعت");
    $("report").value = source.report || "";
    alert("اطلاعات روز قبل کپی شد. برای ثبت آن، ذخیره کارکرد را بزنید.");
});

function renderRecords() {
    const container = $("allRecords");
    const search = $("searchInput").value.trim();
    container.innerHTML = "";
    const filtered = sortedRecords().filter(record => {
        const date = formatDate(record.date);
        return date.includes(search) || (record.report || "").includes(search);
    });
    if (!filtered.length) { container.textContent = "موردی پیدا نشد."; return; }
    filtered.forEach(record => container.appendChild(createRecordCard(record)));
}
$("searchInput").addEventListener("input", renderRecords);

function renderReports() {
    const container = $("reportContent");
    container.innerHTML = "";
    const totalAdvance = records.reduce((sum, record) => sum + advanceTotal(record), 0);
    const totalOvertime = records.reduce((sum, record) => sum + overtimeTotal(record), 0);
    const items = [
        ["روزهای ثبت‌شده", records.length],
        ["روزهای دارای اضافه‌کاری", totalOvertimeDays()],
        ["مجموع مساعده‌ها", totalAdvance],
        ["مجموع ساعت اضافه‌کاری", totalOvertime]
    ];
    items.forEach(([label, value]) => {
        const card = document.createElement("div");
        card.className = "report-card";
        const title = document.createElement("span");
        title.textContent = label;
        const number = document.createElement("strong");
        number.textContent = formatNumber(value);
        card.append(title, number);
        container.appendChild(card);
    });
}

$("themeBtn").addEventListener("click", () => {
    document.body.classList.toggle("light-theme");
    localStorage.setItem("karbanTheme", document.body.classList.contains("light-theme") ? "light" : "dark");
});
if (localStorage.getItem("karbanTheme") === "light") document.body.classList.add("light-theme");

function renderEverything() {
    renderHome();
    renderWeek();
    renderRecords();
    renderReports();
}

function createBackupPayload() {
    return JSON.stringify({
        app: "KARBAN",
        formatVersion: 1,
        exportedAt: new Date().toISOString(),
        records
    }, null, 2);
}

function exportBackup() {
    const json = createBackupPayload();
    if (window.KarbanStorage && typeof window.KarbanStorage.exportBackup === "function") {
        window.KarbanStorage.exportBackup(json);
        return;
    }
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "KARBAN-backup.json";
    link.click();
    URL.revokeObjectURL(url);
}

function importBackup() {
    if (window.KarbanStorage && typeof window.KarbanStorage.importBackup === "function") {
        window.KarbanStorage.importBackup();
        return;
    }
    const picker = document.createElement("input");
    picker.type = "file";
    picker.accept = ".json,application/json";
    picker.addEventListener("change", async () => {
        const file = picker.files && picker.files[0];
        if (!file) return;
        try {
            window.importBackupFromNative(await file.text());
        } catch {
            alert("خواندن فایل پشتیبان ناموفق بود.");
        }
    });
    picker.click();
}

window.onNativeBackupExported = success => {
    alert(success ? "فایل پشتیبان با موفقیت ذخیره شد." : "ذخیره فایل پشتیبان ناموفق بود.");
};

window.onNativeBackupImported = success => {
    if (!success) alert("بازیابی فایل پشتیبان ناموفق بود.");
};

window.importBackupFromNative = raw => {
    try {
        const payload = JSON.parse(raw);
        if (!payload || payload.app !== "KARBAN" || payload.formatVersion !== 1 ||
            !Array.isArray(payload.records) || payload.records.length > 10000) {
            throw new Error("Invalid backup format");
        }

        const restored = payload.records.map(item => {
            if (!item || typeof item !== "object" || typeof item.id !== "string" || item.id.length > 100 ||
                typeof item.date !== "string" || !/^\\d{4}-\\d{2}-\\d{2}$/.test(item.date) ||
                !Array.isArray(item.advances) || !Array.isArray(item.overtimes) ||
                item.advances.length > 100 || item.overtimes.length > 100) {
                throw new Error("Invalid record");
            }
            const cleanRows = rows => rows.map(row => {
                if (!row || typeof row !== "object" || !Number.isFinite(Number(row.value)) ||
                    Number(row.value) < 0 || Number(row.value) > 1000000000 ||
                    String(row.title || "").length > 200) {
                    throw new Error("Invalid row");
                }
                return { title: String(row.title || ""), value: Number(row.value) };
            });
            const report = String(item.report || "");
            if (report.length > 5000) throw new Error("Report too long");
            return {
                id: item.id,
                date: item.date,
                advances: cleanRows(item.advances),
                overtimes: cleanRows(item.overtimes),
                report
            };
        });

        if (!confirm("با بازیابی این فایل، سوابق فعلی با اطلاعات داخل پشتیبان جایگزین می‌شوند. ادامه می‌دهید؟")) return;
        const previousRecords = records;
        records = restored;
        if (!saveRecords()) {
            records = previousRecords;
            alert("ذخیره اطلاعات بازیابی‌شده ناموفق بود. سوابق قبلی حفظ شدند.");
            return;
        }
        renderEverything();
        openPage("home");
        alert("اطلاعات با موفقیت بازیابی شد.");
    } catch (error) {
        console.error("Backup import failed:", error);
        alert("فایل پشتیبان معتبر نیست یا ساختار آن پشتیبانی نمی‌شود.");
    }
};


selectedDate = todayISO();
renderEverything();
openPage("home");
