package com.vrticconnect.core.attendance

/** Physical attendance status of a child for a given day. */
enum class AttendanceStatus { NOT_ARRIVED, CHECKED_IN, CHECKED_OUT }

/**
 * One child's state for the day.
 *
 * @param expected true when the child is on the day's schedule (planned population)
 * @param status physical check-in/out status
 * @param absent an absence was reported for the day
 * @param late the child arrived (or is) late relative to the expected time
 */
data class ChildDayStatus(
    val expected: Boolean,
    val status: AttendanceStatus,
    val absent: Boolean = false,
    val late: Boolean = false,
)

/**
 * Daily overview counters (REQUIREMENTS_BRIEF §11).
 *
 * Invariants:
 * - `expected == present + departed + absent + notArrived` (each planned child lands in exactly one bucket)
 * - `physicallyPresent == present + unscheduledPresent`
 *
 * `late` is informational and overlaps with the buckets above (a late child is usually also present).
 */
data class DailyCounters(
    val expected: Int,
    val present: Int,
    val departed: Int,
    val absent: Int,
    val notArrived: Int,
    val late: Int,
    val unscheduledPresent: Int,
    val physicallyPresent: Int,
) {
    companion object {
        val EMPTY = DailyCounters(0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Computes [DailyCounters] from the day's list of children. This is THE shared definition
 * used by mobile and (by contract) mirrored by the backend.
 *
 * Bucket rules for an **expected** child (physical status wins over the absence flag, because
 * reporting an absence for a child who is present must not check the child out — §11):
 * - CHECKED_IN  → present
 * - CHECKED_OUT → departed
 * - NOT_ARRIVED + absent → absent
 * - NOT_ARRIVED          → notArrived
 *
 * An **unscheduled** child (expected = false) is counted only as `unscheduledPresent` while
 * CHECKED_IN; once checked out (or never arrived) it does not affect any counter.
 *
 * `late` counts expected children flagged late, regardless of bucket.
 */
fun computeDailyCounters(children: List<ChildDayStatus>): DailyCounters {
    var present = 0
    var departed = 0
    var absent = 0
    var notArrived = 0
    var late = 0
    var unscheduledPresent = 0

    for (child in children) {
        if (child.expected) {
            when (child.status) {
                AttendanceStatus.CHECKED_IN -> present++
                AttendanceStatus.CHECKED_OUT -> departed++
                AttendanceStatus.NOT_ARRIVED -> if (child.absent) absent++ else notArrived++
            }
            if (child.late) late++
        } else if (child.status == AttendanceStatus.CHECKED_IN) {
            unscheduledPresent++
        }
    }

    return DailyCounters(
        expected = present + departed + absent + notArrived,
        present = present,
        departed = departed,
        absent = absent,
        notArrived = notArrived,
        late = late,
        unscheduledPresent = unscheduledPresent,
        physicallyPresent = present + unscheduledPresent,
    )
}
