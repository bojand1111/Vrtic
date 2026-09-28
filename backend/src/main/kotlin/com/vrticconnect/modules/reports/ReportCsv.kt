package com.vrticconnect.modules.reports

/**
 * CSV for Excel with a Serbian locale: UTF-8 with BOM (so č ć š ž đ show correctly), `;` separator,
 * decimal comma for sr, CRLF line ends. Cells starting with = + - @ are prefixed with ' (formula injection).
 * Only names, group names and counts: never health data.
 */
object ReportCsv {
    const val BOM = "﻿"

    enum class Lang { SR_LATN, SR_CYRL, EN }

    fun langOf(acceptLanguage: String?): Lang {
        val first = acceptLanguage?.split(',')?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            first.startsWith("sr-cyrl") -> Lang.SR_CYRL
            first.startsWith("en") -> Lang.EN
            else -> Lang.SR_LATN
        }
    }

    private val HEADERS = mapOf(
        Lang.SR_LATN to listOf("Grupa", "Dete", "Očekivano dana", "Prisutno dana", "Odsutno: bolest", "Odsutno: odmor", "Odsutno: ostalo", "Nije došlo", "Neplanirano prisutno", "Kasni dolasci", "Prisustvo %"),
        Lang.SR_CYRL to listOf("Група", "Дете", "Очекивано дана", "Присутно дана", "Одсутно: болест", "Одсутно: одмор", "Одсутно: остало", "Није дошло", "Непланирано присутно", "Касни доласци", "Присуство %"),
        Lang.EN to listOf("Group", "Child", "Expected days", "Present days", "Absent: sick", "Absent: vacation", "Absent: other", "Not arrived", "Unscheduled present", "Late arrivals", "Attendance %"),
    )
    private val TOTAL = mapOf(Lang.SR_LATN to "Ukupno", Lang.SR_CYRL to "Укупно", Lang.EN to "Total")
    private val PERIOD = mapOf(Lang.SR_LATN to "Period", Lang.SR_CYRL to "Период", Lang.EN to "Period")

    fun attendance(report: AttendanceSummaryReport, lang: Lang): String {
        val sb = StringBuilder(BOM)
        fun row(cells: List<String>) { sb.append(cells.joinToString(";") { cell(it) }).append("\r\n") }
        fun pct(v: Double) = if (lang == Lang.EN) v.toString() else v.toString().replace('.', ',')
        row(listOf(PERIOD.getValue(lang), "${report.from} - ${report.countedTo ?: report.to}"))
        row(HEADERS.getValue(lang))
        for (g in report.byGroup) {
            for (ch in report.byChild.filter { it.groupId == g.groupId }) {
                row(
                    listOf(
                        ch.groupName, "${ch.familyName} ${ch.givenName}", "${ch.expectedDays}", "${ch.presentDays}", "${ch.absentSickDays}",
                        "${ch.absentVacationDays}", "${ch.absentOtherDays}", "${ch.notArrivedDays}", "${ch.unscheduledDays}", "${ch.lateArrivals}", pct(ch.attendanceRatePct),
                    ),
                )
            }
            val kids = report.byChild.filter { it.groupId == g.groupId }
            row(
                listOf(
                    g.groupName, TOTAL.getValue(lang), "${g.expectedChildDays}", "${g.presentChildDays}", "${kids.sumOf { it.absentSickDays }}",
                    "${kids.sumOf { it.absentVacationDays }}", "${kids.sumOf { it.absentOtherDays }}", "${g.notArrivedChildDays}", "${g.unscheduledChildDays}",
                    "${g.lateArrivals}", pct(g.attendanceRatePct),
                ),
            )
        }
        val t = report.totals
        val all = report.byChild
        row(
            listOf(
                TOTAL.getValue(lang), "", "${t.expectedChildDays}", "${t.presentChildDays}", "${all.sumOf { it.absentSickDays }}", "${all.sumOf { it.absentVacationDays }}",
                "${all.sumOf { it.absentOtherDays }}", "${t.notArrivedChildDays}", "${t.unscheduledChildDays}", "${t.lateArrivals}", pct(t.attendanceRatePct),
            ),
        )
        return sb.toString()
    }

    fun cell(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return if (safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
