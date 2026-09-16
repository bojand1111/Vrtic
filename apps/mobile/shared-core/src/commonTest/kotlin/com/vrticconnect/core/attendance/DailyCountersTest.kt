package com.vrticconnect.core.attendance

import com.vrticconnect.core.attendance.AttendanceStatus.CHECKED_IN
import com.vrticconnect.core.attendance.AttendanceStatus.CHECKED_OUT
import com.vrticconnect.core.attendance.AttendanceStatus.NOT_ARRIVED
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyCountersTest {

    /** Every combination of the four input dimensions, so the invariants are checked exhaustively. */
    private val allCombinations: List<ChildDayStatus> =
        listOf(true, false).flatMap { expected ->
            AttendanceStatus.entries.flatMap { status ->
                listOf(true, false).flatMap { absent ->
                    listOf(true, false).map { late ->
                        ChildDayStatus(expected = expected, status = status, absent = absent, late = late)
                    }
                }
            }
        }

    @Test
    fun emptyListGivesEmptyCounters() {
        assertEquals(DailyCounters.EMPTY, computeDailyCounters(emptyList()))
    }

    @Test
    fun expectedEqualsPresentPlusDepartedPlusAbsentPlusNotArrived_forEverySubset() {
        // Check the invariant on each single child and on the full population.
        val samples = allCombinations.map { listOf(it) } + listOf(allCombinations)
        for (sample in samples) {
            val c = computeDailyCounters(sample)
            assertEquals(c.present + c.departed + c.absent + c.notArrived, c.expected, "formula for $sample")
            assertEquals(sample.count { it.expected }, c.expected, "expected == planned population for $sample")
            assertEquals(c.present + c.unscheduledPresent, c.physicallyPresent, "physicallyPresent for $sample")
        }
    }

    @Test
    fun expectedChildrenAreBucketedByPhysicalStatusThenAbsence() {
        val children = listOf(
            ChildDayStatus(expected = true, status = CHECKED_IN),
            ChildDayStatus(expected = true, status = CHECKED_IN, late = true),
            ChildDayStatus(expected = true, status = CHECKED_OUT),
            ChildDayStatus(expected = true, status = NOT_ARRIVED, absent = true),
            ChildDayStatus(expected = true, status = NOT_ARRIVED),
            ChildDayStatus(expected = true, status = NOT_ARRIVED),
        )
        val c = computeDailyCounters(children)
        assertEquals(6, c.expected)
        assertEquals(2, c.present)
        assertEquals(1, c.departed)
        assertEquals(1, c.absent)
        assertEquals(2, c.notArrived)
        assertEquals(1, c.late)
        assertEquals(0, c.unscheduledPresent)
        assertEquals(2, c.physicallyPresent)
    }

    @Test
    fun absenceReportedForPresentChildDoesNotCheckOutOrCountAsAbsent() {
        val c = computeDailyCounters(listOf(ChildDayStatus(expected = true, status = CHECKED_IN, absent = true)))
        assertEquals(1, c.present)
        assertEquals(0, c.absent)
        assertEquals(1, c.expected)
    }

    @Test
    fun unscheduledChildrenAreCountedSeparatelyAndIncludedInPhysicallyPresent() {
        val children = listOf(
            ChildDayStatus(expected = true, status = CHECKED_IN),
            ChildDayStatus(expected = false, status = CHECKED_IN),
            ChildDayStatus(expected = false, status = CHECKED_IN, late = true),
            ChildDayStatus(expected = false, status = CHECKED_OUT),
            ChildDayStatus(expected = false, status = NOT_ARRIVED),
        )
        val c = computeDailyCounters(children)
        assertEquals(1, c.expected, "unscheduled children never enter the planned population")
        assertEquals(1, c.present)
        assertEquals(2, c.unscheduledPresent)
        assertEquals(3, c.physicallyPresent)
        assertEquals(0, c.late, "late is only tracked for the planned population")
        assertEquals(0, c.departed)
        assertEquals(0, c.notArrived)
    }
}
