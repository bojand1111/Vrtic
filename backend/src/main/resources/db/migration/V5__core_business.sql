-- =====================================================================================
--  V5 core business tables (EPIC 04-13 subset): children, enrollments, guardians, pickup persons,
--  weekly schedule templates, absences, attendance (events + projections), announcements,
--  consent policies (FK target of calendar events), calendar events, menus.
--  Table definitions are copied verbatim from docs/database/schema.sql;
--  RLS, maintenance policies and privileges follow schema.sql sections 15-16.
--  Not included yet: child_health_profiles, schedule overrides/closures/daily_plans, files/photos,
--  consents decisions, messaging, documents.
-- =====================================================================================

CREATE TABLE app.children (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  given_name       text NOT NULL,
  family_name      text NOT NULL,
  date_of_birth    date NOT NULL CHECK (date_of_birth > DATE '2000-01-01'),
  photo_file_id    uuid,                               -- FK to files added later (files defined in section 9)
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  general_notes    text,                               -- NON-sensitive notes only (e.g. "brings own blanket")
  version          int NOT NULL DEFAULT 1,             -- optimistic concurrency (If-Match)
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  UNIQUE (id, organization_id)
);
CREATE INDEX ON app.children (organization_id, family_name, given_name) WHERE deleted_at IS NULL;
CREATE TRIGGER children_touch BEFORE UPDATE ON app.children FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

ALTER TABLE app.invitations
  ADD CONSTRAINT invitations_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id);

-- A child belongs to exactly one group at any given date. History is kept via non-overlapping periods.
CREATE TABLE app.enrollments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id         uuid NOT NULL,
  group_id         uuid NOT NULL,
  valid_from       date NOT NULL,
  valid_to         date,                               -- NULL = open ended
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
  end_reason       text,
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, organization_id) REFERENCES app.groups(id, organization_id),
  CHECK (valid_to IS NULL OR valid_to >= valid_from),
  EXCLUDE USING gist (
    child_id WITH =,
    daterange(valid_from, valid_to, '[]') WITH &&
  ) WHERE (status IN ('PLANNED','ACTIVE'))
);
CREATE INDEX ON app.enrollments (organization_id, group_id, valid_from, valid_to);
CREATE INDEX ON app.enrollments (organization_id, child_id);
CREATE TRIGGER enrollments_touch BEFORE UPDATE ON app.enrollments FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Guardian link = PARENT membership <-> child, confirmed by staff. Parents cannot self-link.
CREATE TABLE app.guardians (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id      uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id             uuid NOT NULL,
  membership_id        uuid NOT NULL,                  -- must be a PARENT membership (enforced in app + trigger)
  relationship         text NOT NULL CHECK (relationship IN ('MOTHER','FATHER','LEGAL_GUARDIAN','GRANDPARENT','OTHER')),
  status               text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','REVOKED')),
  is_primary           boolean NOT NULL DEFAULT false,
  can_manage_schedule  boolean NOT NULL DEFAULT true,
  can_report_absence   boolean NOT NULL DEFAULT true,
  can_give_consent     boolean NOT NULL DEFAULT true,
  can_view_health      boolean NOT NULL DEFAULT true,  -- guardian's own child; staff still need CHILD_HEALTH_READ
  confirmed_by         uuid REFERENCES app.users(id),  -- staff user
  confirmed_at         timestamptz,
  revoked_by           uuid REFERENCES app.users(id),
  revoked_at           timestamptz,
  revoke_reason        text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id)      REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE,
  CHECK (status <> 'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)),
  CHECK (status <> 'REVOKED'   OR revoked_at IS NOT NULL)
);
CREATE UNIQUE INDEX guardians_unique_link ON app.guardians (child_id, membership_id) WHERE status <> 'REVOKED';
CREATE INDEX ON app.guardians (organization_id, membership_id) WHERE status = 'CONFIRMED';
CREATE TRIGGER guardians_touch BEFORE UPDATE ON app.guardians FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Basic list of people authorised to pick up the child (P0). Digital pickup verification is future scope.
CREATE TABLE app.pickup_persons (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id        uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id               uuid NOT NULL,
  full_name              text NOT NULL,
  relationship           text,
  phone                  text,
  note                   text,
  valid_from             date,
  valid_to               date,
  added_by_membership_id uuid NOT NULL,
  status                 text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
  revoked_at             timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (child_id, organization_id)               REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (added_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ON app.pickup_persons (organization_id, child_id) WHERE status = 'ACTIVE';
CREATE TRIGGER pickup_persons_touch BEFORE UPDATE ON app.pickup_persons FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Recurring weekly template, valid from effective_from. A new template supersedes the old one
-- from its effective date; historical days keep the plan that was frozen for them.
CREATE TABLE app.schedule_templates (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id        uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id               uuid NOT NULL,
  effective_from         date NOT NULL,
  effective_to           date,                         -- set when superseded
  created_by_membership_id uuid NOT NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  version                int NOT NULL DEFAULT 1,
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (effective_to IS NULL OR effective_to >= effective_from),
  EXCLUDE USING gist (child_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);
CREATE INDEX ON app.schedule_templates (organization_id, child_id, effective_from DESC);

CREATE TABLE app.schedule_template_days (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  template_id      uuid NOT NULL,
  weekday          smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO
  attends          boolean NOT NULL,
  arrival_time     time,                                                 -- local time in org timezone
  departure_time   time,
  FOREIGN KEY (template_id, organization_id) REFERENCES app.schedule_templates(id, organization_id) ON DELETE CASCADE,
  UNIQUE (template_id, weekday),
  CHECK ((attends AND arrival_time IS NOT NULL AND departure_time IS NOT NULL AND arrival_time < departure_time)
         OR (NOT attends AND arrival_time IS NULL AND departure_time IS NULL))
);

CREATE TABLE app.absences (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id          uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id                 uuid NOT NULL,
  kind                     text NOT NULL CHECK (kind IN ('SICK','VACATION','OTHER')),
  date_from                date NOT NULL,
  date_to                  date NOT NULL,              -- inclusive
  note                     text,                       -- optional, non-medical
  status                   text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
  reported_by_membership_id uuid NOT NULL,
  cancelled_by_membership_id uuid,
  cancelled_at             timestamptz,
  version                  int NOT NULL DEFAULT 1,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (reported_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (date_to >= date_from AND date_to <= date_from + 365),
  CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
  EXCLUDE USING gist (child_id WITH =, daterange(date_from, date_to, '[]') WITH &&) WHERE (status = 'ACTIVE')
);
CREATE INDEX ON app.absences (organization_id, date_from, date_to) WHERE status = 'ACTIVE';
CREATE TRIGGER absences_touch BEFORE UPDATE ON app.absences FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Projection: one row per child per date. `version` increments on every applied event.
-- Commands lock this row (SELECT ... FOR UPDATE) and compare expected_version.
CREATE TABLE app.attendance_days (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id     uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id            uuid NOT NULL,
  group_id            uuid,                            -- group the child was checked into
  attendance_date     date NOT NULL,                   -- in organization timezone
  status              text NOT NULL DEFAULT 'NOT_ARRIVED' CHECK (status IN ('NOT_ARRIVED','CHECKED_IN','CHECKED_OUT')),
  absence_kind        text CHECK (absence_kind IN ('SICK','VACATION','OTHER')),  -- context, independent of status
  is_expected         boolean NOT NULL DEFAULT true,   -- copied from daily_plans at first event / freeze
  expected_arrival    time,
  expected_departure  time,
  is_unscheduled      boolean NOT NULL DEFAULT false,  -- present although not expected
  first_check_in_at   timestamptz,
  last_check_out_at   timestamptz,
  open_visit_id       uuid,                            -- non-NULL iff status = CHECKED_IN
  visits_count        int NOT NULL DEFAULT 0,
  version             int NOT NULL DEFAULT 0,
  last_event_id       uuid,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (child_id, attendance_date),
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, organization_id) REFERENCES app.groups(id, organization_id),
  CHECK ((status = 'CHECKED_IN') = (open_visit_id IS NOT NULL))
);
CREATE INDEX ON app.attendance_days (organization_id, group_id, attendance_date);

CREATE TABLE app.attendance_visits (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id    uuid NOT NULL,
  attendance_day_id  uuid NOT NULL,
  sequence_no        int NOT NULL CHECK (sequence_no >= 1),
  check_in_at        timestamptz NOT NULL,
  check_out_at       timestamptz,
  check_in_event_id  uuid NOT NULL,
  check_out_event_id uuid,
  FOREIGN KEY (attendance_day_id, organization_id) REFERENCES app.attendance_days(id, organization_id) ON DELETE CASCADE,
  UNIQUE (attendance_day_id, sequence_no),
  CHECK (check_out_at IS NULL OR check_out_at >= check_in_at)
);
CREATE UNIQUE INDEX attendance_visits_one_open ON app.attendance_visits (attendance_day_id) WHERE check_out_at IS NULL;

-- Immutable event log. command_id is the client idempotency key (UUID generated on device).
CREATE TABLE app.attendance_events (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id      uuid NOT NULL,
  attendance_day_id    uuid NOT NULL,
  child_id             uuid NOT NULL,
  event_type           text NOT NULL CHECK (event_type IN ('CHECK_IN','CHECK_OUT','CORRECTION','ABSENCE_MARKED','ABSENCE_CLEARED','UNSCHEDULED_PRESENT')),
  occurred_at          timestamptz NOT NULL,           -- business time (may be earlier when synced offline)
  recorded_at          timestamptz NOT NULL DEFAULT now(),
  actor_membership_id  uuid NOT NULL,
  source               text NOT NULL CHECK (source IN ('MOBILE','WEB','OFFLINE_SYNC','SYSTEM')),
  command_id           uuid NOT NULL,
  device_id            text,
  resulting_version    int NOT NULL,
  correction_of_event_id uuid REFERENCES app.attendance_events(id),
  correction_reason    text,
  payload              jsonb NOT NULL DEFAULT '{}'::jsonb,
  FOREIGN KEY (attendance_day_id, organization_id) REFERENCES app.attendance_days(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (child_id, organization_id)          REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (actor_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  UNIQUE (command_id),
  CHECK (event_type <> 'CORRECTION' OR correction_reason IS NOT NULL)
);
CREATE INDEX ON app.attendance_events (attendance_day_id, recorded_at);
CREATE INDEX ON app.attendance_events (organization_id, recorded_at DESC);
CREATE TRIGGER attendance_events_append_only BEFORE UPDATE OR DELETE ON app.attendance_events FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

ALTER TABLE app.attendance_visits
  ADD CONSTRAINT attendance_visits_in_event_fk  FOREIGN KEY (check_in_event_id)  REFERENCES app.attendance_events(id),
  ADD CONSTRAINT attendance_visits_out_event_fk FOREIGN KEY (check_out_event_id) REFERENCES app.attendance_events(id);
ALTER TABLE app.attendance_days
  ADD CONSTRAINT attendance_days_open_visit_fk FOREIGN KEY (open_visit_id) REFERENCES app.attendance_visits(id) DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT attendance_days_last_event_fk FOREIGN KEY (last_event_id) REFERENCES app.attendance_events(id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE app.announcements (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  title            text NOT NULL CHECK (length(title) <= 200),
  body             text NOT NULL,                      -- plain text / limited markdown; rendered escaped
  status           text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  publish_at       timestamptz,
  expires_at       timestamptz,
  published_at     timestamptz,
  created_by_membership_id uuid NOT NULL,
  version          int NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  UNIQUE (id, organization_id),
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (expires_at IS NULL OR publish_at IS NULL OR expires_at > publish_at),
  CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ON app.announcements (organization_id, status, publish_at DESC);
CREATE TRIGGER announcements_touch BEFORE UPDATE ON app.announcements FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Audience definition (rule). Evaluated at publish time into announcement_recipients (snapshot)
-- AND re-checked against current rights on every read.
CREATE TABLE app.announcement_audiences (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  announcement_id  uuid NOT NULL,
  audience_type    text NOT NULL CHECK (audience_type IN ('ORGANIZATION','LOCATION','GROUP','GUARDIAN')),
  location_id      uuid,
  group_id         uuid,
  membership_id    uuid,
  FOREIGN KEY (announcement_id, organization_id) REFERENCES app.announcements(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (location_id, organization_id)     REFERENCES app.locations(id, organization_id),
  FOREIGN KEY (group_id, organization_id)        REFERENCES app.groups(id, organization_id),
  FOREIGN KEY (membership_id, organization_id)   REFERENCES app.organization_memberships(id, organization_id),
  CHECK (
    (audience_type = 'ORGANIZATION' AND location_id IS NULL AND group_id IS NULL AND membership_id IS NULL) OR
    (audience_type = 'LOCATION'     AND location_id IS NOT NULL AND group_id IS NULL AND membership_id IS NULL) OR
    (audience_type = 'GROUP'        AND group_id IS NOT NULL AND location_id IS NULL AND membership_id IS NULL) OR
    (audience_type = 'GUARDIAN'     AND membership_id IS NOT NULL AND location_id IS NULL AND group_id IS NULL)
  )
);
CREATE INDEX ON app.announcement_audiences (announcement_id);

-- Snapshot of recipients at publish time + read tracking. A read is explicit (client opened it).
CREATE TABLE app.announcement_recipients (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  announcement_id  uuid NOT NULL,
  membership_id    uuid NOT NULL,
  snapshot_at      timestamptz NOT NULL DEFAULT now(),
  read_at          timestamptz,
  FOREIGN KEY (announcement_id, organization_id) REFERENCES app.announcements(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (membership_id, organization_id)   REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE,
  UNIQUE (announcement_id, membership_id)
);
CREATE INDEX ON app.announcement_recipients (membership_id, read_at);

-- Versioned consent wording per purpose and locale. wording_hash = sha256(wording_text).
CREATE TABLE app.consent_policies (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  purpose          text NOT NULL CHECK (purpose IN ('PHOTO_INTERNAL_GALLERY','PHOTO_GROUP_SHARING','TRIP_PARTICIPATION','DATA_PROCESSING_BASIC')),
  version          int  NOT NULL CHECK (version >= 1),
  locale           text NOT NULL CHECK (locale IN ('sr-Latn','sr-Cyrl','en')),
  wording_text     text NOT NULL,
  wording_hash     bytea NOT NULL,
  effective_from   date NOT NULL,
  retired_at       timestamptz,
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  UNIQUE (organization_id, purpose, version, locale)
);

CREATE TABLE app.calendar_events (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  kind             text NOT NULL CHECK (kind IN ('TRIP','PHOTO_DAY','PERFORMANCE','HOLIDAY','PARENT_MEETING','CLOSURE','OTHER')),
  title            text NOT NULL CHECK (length(title) <= 200),
  description      text,
  location_id      uuid,                               -- NULL = whole organization
  group_id         uuid,                               -- NULL = whole location/organization
  all_day          boolean NOT NULL,
  starts_on        date NOT NULL,                      -- for all-day events (inclusive)
  ends_on          date NOT NULL,
  starts_at        timestamptz,                        -- for timed events
  ends_at          timestamptz,
  timezone         text NOT NULL DEFAULT 'Europe/Belgrade',
  requires_consent_policy_id uuid,                     -- e.g. TRIP_PARTICIPATION
  created_by_membership_id uuid NOT NULL,
  version          int NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  FOREIGN KEY (location_id, organization_id) REFERENCES app.locations(id, organization_id),
  FOREIGN KEY (group_id, organization_id)    REFERENCES app.groups(id, organization_id),
  FOREIGN KEY (requires_consent_policy_id, organization_id) REFERENCES app.consent_policies(id, organization_id),
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (ends_on >= starts_on),
  CHECK (all_day OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at > starts_at))
);
CREATE INDEX ON app.calendar_events (organization_id, starts_on, ends_on) WHERE deleted_at IS NULL;
CREATE TRIGGER calendar_events_touch BEFORE UPDATE ON app.calendar_events FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.menu_days (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  location_id      uuid,                               -- NULL = whole organization
  menu_date        date NOT NULL,
  is_published     boolean NOT NULL DEFAULT false,
  note             text,
  version          int NOT NULL DEFAULT 1,
  created_by_membership_id uuid NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (location_id, organization_id) REFERENCES app.locations(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id)
);
CREATE UNIQUE INDEX menu_days_org_unique ON app.menu_days (organization_id, menu_date) WHERE location_id IS NULL;
CREATE UNIQUE INDEX menu_days_loc_unique ON app.menu_days (location_id, menu_date) WHERE location_id IS NOT NULL;
CREATE TRIGGER menu_days_touch BEFORE UPDATE ON app.menu_days FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.menu_items (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  menu_day_id      uuid NOT NULL,
  meal_slot        text NOT NULL CHECK (meal_slot IN ('BREAKFAST','SNACK_AM','LUNCH','SNACK_PM')),  -- two distinct snack ids
  description      text NOT NULL,
  allergen_tags    text[] NOT NULL DEFAULT '{}',        -- informational tags; NOT medical advice
  sort_order       int NOT NULL DEFAULT 0,
  FOREIGN KEY (menu_day_id, organization_id) REFERENCES app.menu_days(id, organization_id) ON DELETE CASCADE,
  UNIQUE (menu_day_id, meal_slot, sort_order)
);


-- ---------------------------------------------------------------- RLS (tenant isolation + owner maintenance)
DO $$
DECLARE
  t text;
  tenant_tables text[] := ARRAY[
    'children',
    'enrollments',
    'guardians',
    'pickup_persons',
    'schedule_templates',
    'schedule_template_days',
    'absences',
    'attendance_days',
    'attendance_visits',
    'attendance_events',
    'announcements',
    'announcement_audiences',
    'announcement_recipients',
    'consent_policies',
    'calendar_events',
    'menu_days',
    'menu_items'
  ];
BEGIN
  FOREACH t IN ARRAY tenant_tables LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I FOR ALL TO app_runtime
         USING (organization_id = app.current_organization_id())
         WITH CHECK (organization_id = app.current_organization_id())', t);
    EXECUTE format('CREATE POLICY owner_maintenance ON app.%I FOR ALL TO app_owner USING (app.maintenance_mode()) WITH CHECK (app.maintenance_mode())', t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- privileges (default privileges grant SELECT/INSERT/UPDATE)
GRANT SELECT, INSERT, UPDATE ON app.children, app.enrollments, app.guardians, app.pickup_persons, app.schedule_templates, app.schedule_template_days, app.absences, app.attendance_days, app.attendance_visits, app.attendance_events, app.announcements, app.announcement_audiences, app.announcement_recipients, app.consent_policies, app.calendar_events, app.menu_days, app.menu_items TO app_runtime, app_worker;
GRANT DELETE ON app.announcement_audiences, app.schedule_template_days, app.menu_items TO app_runtime;
REVOKE UPDATE ON app.attendance_events FROM app_runtime, app_worker;
