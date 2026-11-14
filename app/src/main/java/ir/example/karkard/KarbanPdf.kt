package ir.example.karkard

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.time.LocalDate

object KarbanPdf {
    fun write(context: Context, uri: Uri, records: List<WorkRecord>, scope: String) {
        val now = LocalDate.now()
        val jalali = jalaliParts(now)
        val jy = jalali.first
        val jm = jalali.second
        fun faDate(iso: String): String = persianDate(iso)
        val range = when (scope) {
            "day" -> faDate(now.toString())
            "week" -> { val start = now.minusDays(((now.dayOfWeek.value + 1) % 7).toLong()); "${faDate(start.toString())} تا ${faDate(start.plusDays(6).toString())}" }
            "month" -> "$jm/$jy"
            "year" -> jy.toString()
            else -> "تمام سوابق"
        }
        val title = when (scope) { "day" -> "گزارش روزانه"; "week" -> "گزارش هفتگی"; "month" -> "گزارش ماهانه"; "year" -> "گزارش سالانه"; else -> "گزارش کامل" }
        val advances = records.sumOf { r -> r.advances.sumOf { it.value } }
        val overtime = records.sumOf { r -> r.overtimes.sumOf { it.value } }
        val overtimePay = records.sumOf { r -> r.overtimes.sumOf { it.amount } }
        val text = buildString {
            appendLine("KARBAN | گزارش کارکرد")
            appendLine(title)
            appendLine("بازه گزارش: $range")
            appendLine("تاریخ تهیه: ${faDate(now.toString())}")
            appendLine("----------------------------------------")
            appendLine("خلاصه گزارش")
            appendLine("تعداد روزهای ثبت‌شده: ${records.size}")
            appendLine("مجموع مساعده: ${advances.toLong()} تومان")
            appendLine("مجموع اضافه‌کاری: $overtime ساعت")
            appendLine("مبلغ اضافه‌کاری: ${overtimePay.toLong()} تومان")
            appendLine("----------------------------------------")
            appendLine("جزئیات روزها")
            if (records.isEmpty()) appendLine("برای این بازه گزارشی ثبت نشده است.")
            records.sortedByDescending { it.date }.forEach { r ->
                appendLine("تاریخ: ${faDate(r.date)}")
                appendLine("مساعده: ${r.advances.sumOf { it.value }.toLong()} تومان")
                appendLine("اضافه‌کاری: ${r.overtimes.sumOf { it.value }} ساعت")
                appendLine("مبلغ اضافه‌کاری: ${r.overtimes.sumOf { it.amount }.toLong()} تومان")
                if (r.report.isNotBlank()) appendLine("یادداشت: ${r.report}")
                appendLine("----------------------------------------")
            }
            appendLine("تهیه‌شده با KARBAN 3.0")
            appendLine("سازنده: محمد جواد احمدی")
        }
        val width = 595; val height = 842; val margin = 42
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE).setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(true).setLineSpacing(4f, 1.1f).build()
        val contentHeight = height - margin * 2
        val pages = maxOf(1, (layout.height + contentHeight - 1) / contentHeight)
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val info = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                val page = pdf.startPage(info)
                page.canvas.save(); page.canvas.clipRect(margin, margin, width - margin, height - margin)
                page.canvas.translate(margin.toFloat(), (margin - index * contentHeight).toFloat()); layout.draw(page.canvas); page.canvas.restore()
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.GRAY; textSize = 9f }
                page.canvas.drawText("KARBAN 3.0  |  ${index + 1}/$pages", margin.toFloat(), (height - 18).toFloat(), footer)
                pdf.finishPage(page)
            }
            context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: error("PDF output stream unavailable")
        } finally { pdf.close() }
    }
}