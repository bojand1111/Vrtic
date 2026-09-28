/** docs/openapi.yaml DailyOverviewCounters. */
export interface DailyOverviewCounters {
  readonly expected: number;
  readonly present: number;
  readonly departed: number;
  readonly absent: number;
  readonly notArrived: number;
  readonly late: number;
  readonly unscheduledPresent: number;
  readonly physicallyPresent: number;
}

export interface DashboardSummary {
  readonly organizationId: string;
  readonly date: string;
  readonly timezone: string;
  readonly counters: DailyOverviewCounters;
  readonly childrenActive: number;
  readonly staffActive: number;
  readonly guardiansPending: number;
  readonly invitationsPending: number;
  readonly absencesToday: number;
  /** Late schedule changes that start on the date (schedule_change_log). */
  readonly lateScheduleChangesToday: number;
  readonly locations: readonly {
    readonly locationId: string;
    readonly name: string;
    readonly groupsCount: number;
    readonly counters: DailyOverviewCounters;
  }[];
  readonly groups: readonly {
    readonly groupId: string;
    readonly locationId: string;
    readonly name: string;
    readonly counters: DailyOverviewCounters;
  }[];
  readonly latestAnnouncements: readonly {
    readonly id: string;
    readonly title: string;
    readonly publishedAt: string | null;
  }[];
  readonly upcomingEvents: readonly {
    readonly id: string;
    readonly kind: string;
    readonly title: string;
    readonly allDay: boolean;
    readonly startsOn: string;
    readonly endsOn: string;
    readonly startsAt: string | null;
  }[];
  readonly generatedAt: string;
}
