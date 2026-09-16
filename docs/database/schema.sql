-- =====================================================================================
--  Vrtić Connect (kindergarten-platform) — COMPLETE PostgreSQL SCHEMA PROPOSAL
-- =====================================================================================
--  STATUS: DESIGN PROPOSAL. This file is the reference model for ALL epics (P0/P1/P2).
--  It is NOT the executable migration set. Executable Flyway migrations live in
--  backend/src/main/resources/db/migration/ and are introduced epic by epic as
--  subsets of this proposal. Do NOT run this file against a database that already
--  has Flyway migrations applied.
--
--  Target: PostgreSQL 17. Extensions: pgcrypto (digest()), citext, btree_gist
--  (exclusion constraints on ranges).
--
--  Conventions
--   * schema `app` holds all application objects (never `public`).
--   * UUID primary keys (gen_random_uuid()); bigint identity only for append-only logs.
--   * timestamptz for instants (UTC), date for calendar dates, time for local wall-clock
--     times interpreted in the organization's IANA timezone.
--   * Every tenant table has `organization_id uuid NOT NULL` and UNIQUE (id, organization_id)
--     so child tables reference it with a composite FK that pins the tenant.
--   * Soft delete = `deleted_at`; revocation lifecycle = `revoked_at` / `status`.
--   * RLS is ENABLED + FORCED on every tenant table; policies read the transaction-local
--     settings app.organization_id / app.user_id / app.auth_mode / app.platform_mode.
--
--  Roles (created OUTSIDE migrations, see scripts/db/00_roles.sql):
--   * app_owner   — owns schema and objects, runs Flyway. NOSUPERUSER, NOBYPASSRLS.
--   * app_runtime — used by the API. No ownership, NOBYPASSRLS, no DDL. RLS applies (FORCE).
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION app_owner;
SET search_path TO app, public;

-- -------------------------------------------------------------------------------------
--  Context helper functions (STABLE; read transaction-local GUCs set via set_config(.., true))
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.current_organization_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT NULLIF(current_setting('app.organization_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app.current_user_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;

-- 'on' only inside the authentication module while resolving credentials/tokens.
CREATE OR REPLACE FUNCTION app.auth_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.auth_mode', true), '') = 'on'
$$;

-- 'on' only after the application verified an active platform_admins row + MFA.
CREATE OR REPLACE FUNCTION app.platform_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.platform_mode', true), '') = 'on'
$$;

-- Owner-only escape hatch for migrations/seeds/data fixes. FORCE RLS binds the table owner too, so the
-- owner must opt in explicitly per transaction: set_config('app.maintenance_mode','on',true).
-- The runtime/worker roles have no policy that reads this flag, so it is meaningless for them.
CREATE OR REPLACE FUNCTION app.maintenance_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.maintenance_mode', true), '') = 'on'
$$;

-- Generic updated_at trigger
CREATE OR REPLACE FUNCTION app.touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- Append-only guard for audit tables
CREATE OR REPLACE FUNCTION app.deny_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;

-- =====================================================================================
--  1. GLOBAL IDENTITY & AUTH (not tenant-scoped; separate access model, see policies)
-- =====================================================================================

CREATE TABLE app.users (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email               citext NOT NULL,
  email_verified_at   timestamptz,
  password_hash       text,                          -- Argon2id PHC string; NULL = no password yet (invited)
  password_updated_at timestamptz,
  given_name          text NOT NULL,
  family_name         text NOT NULL,
  preferred_locale    text NOT NULL DEFAULT 'sr-Latn' CHECK (preferred_locale IN ('sr-Latn','sr-Cyrl','en')),
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
  failed_login_count  int  NOT NULL DEFAULT 0,
  locked_until        timestamptz,
  last_login_at       timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  deleted_at          timestamptz,
  CONSTRAINT users_email_unique UNIQUE (email)
);
CREATE TRIGGER users_touch BEFORE UPDATE ON app.users FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.email_verification_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  token_hash   bytea NOT NULL UNIQUE,                 -- sha256(token); raw token only in the email link
  new_email    citext,                                -- set when changing email address
  expires_at   timestamptz NOT NULL,                  -- created_at + 24h (configurable)
  consumed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.email_verification_tokens (user_id);

CREATE TABLE app.password_reset_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  token_hash   bytea NOT NULL UNIQUE,
  expires_at   timestamptz NOT NULL,                  -- created_at + 15 min
  consumed_at  timestamptz,
  requested_ip inet,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.password_reset_tokens (user_id);

-- A session = one device/browser login ("session family" for refresh rotation).
CREATE TABLE app.sessions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  client_kind          text NOT NULL CHECK (client_kind IN ('WEB','ANDROID','IOS')),
  device_name          text,
  device_id            text,                          -- app-generated stable id (mobile)
  user_agent           text,
  ip_created           inet,
  mfa_verified_at      timestamptz,                   -- NULL until MFA passed for this session
  reauthenticated_at   timestamptz,                   -- last password/MFA re-entry (sensitive actions)
  created_at           timestamptz NOT NULL DEFAULT now(),
  last_seen_at         timestamptz NOT NULL DEFAULT now(),
  absolute_expires_at  timestamptz NOT NULL,          -- created_at + 30 days
  revoked_at           timestamptz,
  revoke_reason        text CHECK (revoke_reason IN ('USER_LOGOUT','LOGOUT_ALL','ADMIN','REUSE_DETECTED','EXPIRED','PASSWORD_CHANGED'))
);
CREATE INDEX ON app.sessions (user_id) WHERE revoked_at IS NULL;

-- Rotating refresh tokens. Exactly one active (consumed_at IS NULL) token per session.
CREATE TABLE app.refresh_tokens (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id        uuid NOT NULL REFERENCES app.sessions(id) ON DELETE CASCADE,
  token_hash        bytea NOT NULL UNIQUE,
  issued_at         timestamptz NOT NULL DEFAULT now(),
  idle_expires_at   timestamptz NOT NULL,             -- issued_at + 7 days
  consumed_at       timestamptz,
  replaced_by_id    uuid REFERENCES app.refresh_tokens(id),
  reuse_detected_at timestamptz
);
CREATE UNIQUE INDEX refresh_tokens_one_active_per_session ON app.refresh_tokens (session_id) WHERE consumed_at IS NULL;

-- Opaque short-lived access tokens; looked up on every request => instant revocation.
CREATE TABLE app.access_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id  uuid NOT NULL REFERENCES app.sessions(id) ON DELETE CASCADE,
  token_hash  bytea NOT NULL UNIQUE,
  issued_at   timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,                   -- issued_at + 10 min
  revoked_at  timestamptz
);
CREATE INDEX ON app.access_tokens (session_id);
CREATE INDEX ON app.access_tokens (expires_at);      -- cleanup job

CREATE TABLE app.user_mfa_methods (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  type        text NOT NULL CHECK (type IN ('TOTP')),
  secret_enc  bytea NOT NULL,                         -- envelope-encrypted TOTP secret
  key_id      text  NOT NULL,                         -- KMS/data-key identifier
  verified_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at  timestamptz
);
CREATE UNIQUE INDEX user_mfa_one_active_totp ON app.user_mfa_methods (user_id, type) WHERE revoked_at IS NULL;

CREATE TABLE app.user_mfa_recovery_codes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  code_hash  bytea NOT NULL,
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.user_mfa_recovery_codes (user_id) WHERE used_at IS NULL;

-- Brute-force accounting (durable source across instances; may be mirrored in-memory).
CREATE TABLE app.login_attempts (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email_hash   bytea NOT NULL,                        -- sha256(lower(email)); never the raw email
  ip           inet,
  succeeded    boolean NOT NULL,
  occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.login_attempts (email_hash, occurred_at DESC);
CREATE INDEX ON app.login_attempts (ip, occurred_at DESC);

-- Platform-level administrators (SUPER_ADMIN). Global, not a membership.
CREATE TABLE app.platform_admins (
  user_id     uuid PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
  granted_by  uuid REFERENCES app.users(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at  timestamptz,
  note        text
);

-- =====================================================================================
--  2. ORGANIZATIONS, MEMBERSHIPS, PERMISSIONS, SUPPORT ACCESS
-- =====================================================================================

CREATE TABLE app.organizations (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug            citext NOT NULL UNIQUE,
  name            text NOT NULL,
  legal_name      text,
  country_code    char(2) NOT NULL DEFAULT 'RS',
  timezone        text NOT NULL DEFAULT 'Europe/Belgrade',  -- IANA
  default_locale  text NOT NULL DEFAULT 'sr-Latn' CHECK (default_locale IN ('sr-Latn','sr-Cyrl','en')),
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  created_by      uuid REFERENCES app.users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz
);
CREATE TRIGGER organizations_touch BEFORE UPDATE ON app.organizations FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Typed per-organization settings (1:1).
CREATE TABLE app.organization_settings (
  organization_id                 uuid PRIMARY KEY REFERENCES app.organizations(id) ON DELETE CASCADE,
  schedule_change_deadline_hours  int  NOT NULL DEFAULT 12 CHECK (schedule_change_deadline_hours BETWEEN 0 AND 168),
  late_arrival_grace_minutes      int  NOT NULL DEFAULT 15 CHECK (late_arrival_grace_minutes BETWEEN 0 AND 180),
  day_opens_at                    time NOT NULL DEFAULT '06:00',
  day_closes_at                   time NOT NULL DEFAULT '18:00',
  working_weekdays                smallint[] NOT NULL DEFAULT '{1,2,3,4,5}',  -- ISO 1=Mon..7=Sun
  offline_cache_ttl_hours         int NOT NULL DEFAULT 12 CHECK (offline_cache_ttl_hours BETWEEN 1 AND 48),
  updated_at                      timestamptz NOT NULL DEFAULT now(),
  CHECK (day_opens_at < day_closes_at)
);
CREATE TRIGGER organization_settings_touch BEFORE UPDATE ON app.organization_settings FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Roles live on the membership, not on the user. One user may hold different roles in different orgs.
CREATE TABLE app.organization_memberships (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  user_id          uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  role             text NOT NULL CHECK (role IN ('OWNER','ADMIN','TEACHER','PARENT')),
  status           text NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED','ACTIVE','SUSPENDED','REVOKED')),
  invited_by       uuid REFERENCES app.users(id),
  accepted_at      timestamptz,
  suspended_at     timestamptz,
  revoked_at       timestamptz,
  revoke_reason    text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, user_id, role),
  UNIQUE (id, organization_id)
);
CREATE INDEX ON app.organization_memberships (user_id) WHERE status = 'ACTIVE';
CREATE INDEX ON app.organization_memberships (organization_id, role) WHERE status = 'ACTIVE';
CREATE TRIGGER organization_memberships_touch BEFORE UPDATE ON app.organization_memberships FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Fine-grained extra permissions on top of the role (e.g. CHILD_HEALTH_READ for a teacher).
-- Role capabilities themselves are defined in code (docs/SECURITY.md permission matrix).
CREATE TABLE app.membership_permissions (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  membership_id    uuid NOT NULL,
  permission       text NOT NULL CHECK (permission IN (
                     'CHILD_HEALTH_READ','CHILD_HEALTH_WRITE','ATTENDANCE_CORRECT','ANNOUNCEMENT_PUBLISH',
                     'PHOTO_PUBLISH','BILLING_MANAGE','MEMBER_MANAGE','REPORT_EXPORT')),
  granted_by       uuid REFERENCES app.users(id),
  granted_at       timestamptz NOT NULL DEFAULT now(),
  revoked_at       timestamptz,
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX membership_permissions_active ON app.membership_permissions (membership_id, permission) WHERE revoked_at IS NULL;

-- Invitations for staff or guardians (guardian invites carry the child; FK added after children).
CREATE TABLE app.invitations (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  email            citext NOT NULL,
  role             text NOT NULL CHECK (role IN ('OWNER','ADMIN','TEACHER','PARENT')),
  child_id         uuid,
  token_hash       bytea NOT NULL UNIQUE,
  invited_by       uuid NOT NULL REFERENCES app.users(id),
  expires_at       timestamptz NOT NULL,
  accepted_at      timestamptz,
  accepted_user_id uuid REFERENCES app.users(id),
  revoked_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK ((role = 'PARENT') = (child_id IS NOT NULL))
);
CREATE INDEX ON app.invitations (organization_id, email) WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Time-boxed, audited support access for platform staff into ONE organization.
CREATE TABLE app.support_access_grants (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id         uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  support_user_id         uuid NOT NULL REFERENCES app.users(id),
  reason                  text NOT NULL,
  ticket_ref              text NOT NULL,
  scope                   text[] NOT NULL,            -- allowlisted scopes e.g. {ORG_STRUCTURE,ATTENDANCE_READ}
  read_only               boolean NOT NULL DEFAULT true,
  approval_kind           text NOT NULL CHECK (approval_kind IN ('OWNER_APPROVED','EMERGENCY')),
  approved_by             uuid REFERENCES app.users(id),    -- owner user; NULL only for EMERGENCY
  emergency_justification text,
  starts_at               timestamptz NOT NULL DEFAULT now(),
  expires_at              timestamptz NOT NULL,
  revoked_at              timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > starts_at AND expires_at <= starts_at + interval '72 hours'),
  CHECK (approval_kind = 'EMERGENCY' OR approved_by IS NOT NULL),
  CHECK (approval_kind <> 'EMERGENCY' OR emergency_justification IS NOT NULL)
);
CREATE INDEX ON app.support_access_grants (support_user_id, expires_at) WHERE revoked_at IS NULL;

-- =====================================================================================
--  3. ORGANIZATION STRUCTURE: LOCATIONS, GROUPS, EMPLOYEES, ASSIGNMENTS
-- =====================================================================================

CREATE TABLE app.locations (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  name             text NOT NULL,
  address_line     text,
  city             text,
  postal_code      text,
  country_code     char(2) NOT NULL DEFAULT 'RS',
  timezone         text,                               -- optional override of organization timezone
  phone            text,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  UNIQUE (id, organization_id)
);
CREATE UNIQUE INDEX locations_name_unique ON app.locations (organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE TRIGGER locations_touch BEFORE UPDATE ON app.locations FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.groups (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  location_id      uuid NOT NULL,
  name             text NOT NULL,
  age_from_months  int CHECK (age_from_months >= 0),
  age_to_months    int CHECK (age_to_months >= 0),
  capacity         int CHECK (capacity > 0),
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  UNIQUE (id, organization_id),
  FOREIGN KEY (location_id, organization_id) REFERENCES app.locations(id, organization_id),
  CHECK (age_from_months IS NULL OR age_to_months IS NULL OR age_from_months <= age_to_months)
);
CREATE UNIQUE INDEX groups_name_unique ON app.groups (location_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX ON app.groups (organization_id, location_id);
CREATE TRIGGER groups_touch BEFORE UPDATE ON app.groups FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Staff profile bound to an OWNER/ADMIN/TEACHER membership (1:1).
CREATE TABLE app.employees (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id     uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  membership_id       uuid NOT NULL,
  display_name        text NOT NULL,
  job_title           text,
  phone               text,
  primary_location_id uuid,
  started_at          date,
  ended_at            date,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  UNIQUE (membership_id),
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (primary_location_id, organization_id) REFERENCES app.locations(id, organization_id),
  CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);
CREATE TRIGGER employees_touch BEFORE UPDATE ON app.employees FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Teacher <-> group assignment with validity period. Teacher sees the group only inside [valid_from, valid_to].
CREATE TABLE app.group_teacher_assignments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  group_id         uuid NOT NULL,
  employee_id      uuid NOT NULL,
  assignment_role  text NOT NULL DEFAULT 'LEAD' CHECK (assignment_role IN ('LEAD','ASSISTANT','SUBSTITUTE')),
  valid_from       date NOT NULL,
  valid_to         date,                               -- NULL = open ended
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  revoked_at       timestamptz,
  FOREIGN KEY (group_id, organization_id)    REFERENCES app.groups(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (employee_id, organization_id) REFERENCES app.employees(id, organization_id) ON DELETE CASCADE,
  CHECK (valid_to IS NULL OR valid_to >= valid_from),
  -- no overlapping active assignment of the same employee to the same group
  EXCLUDE USING gist (
    employee_id WITH =, group_id WITH =,
    daterange(valid_from, valid_to, '[]') WITH &&
  ) WHERE (revoked_at IS NULL)
);
CREATE INDEX ON app.group_teacher_assignments (organization_id, employee_id) WHERE revoked_at IS NULL;
CREATE INDEX ON app.group_teacher_assignments (organization_id, group_id) WHERE revoked_at IS NULL;

-- =====================================================================================
--  4. CHILDREN, ENROLLMENTS, GUARDIANS, PICKUP PERSONS, HEALTH
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

-- Sensitive health data: separate table, envelope-encrypted JSON payload, separate permission,
-- every read/write audited (audit_log entity_type = CHILD_HEALTH). Never joined into child lists.
-- payload (decrypted) shape: { allergies: [...], specialNeeds: [...], medicalNotes: "...", emergencyContact: {...} }
CREATE TABLE app.child_health_profiles (
  child_id           uuid PRIMARY KEY,
  organization_id    uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  payload_enc        bytea NOT NULL,
  payload_key_id     text  NOT NULL,                   -- data key reference (envelope encryption)
  payload_schema_ver int   NOT NULL DEFAULT 1,
  has_critical_alert boolean NOT NULL DEFAULT false,   -- ONLY a boolean indicator shown to staff ("read the health profile")
  version            int NOT NULL DEFAULT 1,
  updated_by         uuid REFERENCES app.users(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE
);
CREATE TRIGGER child_health_profiles_touch BEFORE UPDATE ON app.child_health_profiles FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- =====================================================================================
--  5. SCHEDULE: TEMPLATES, DAY OVERRIDES, CLOSURES, FROZEN DAILY PLANS
-- =====================================================================================

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

-- Exception for one concrete date (overrides template for that date).
CREATE TABLE app.schedule_day_overrides (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id        uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id               uuid NOT NULL,
  override_date          date NOT NULL,
  attends                boolean NOT NULL,
  arrival_time           time,
  departure_time         time,
  reason                 text,
  is_late_change         boolean NOT NULL DEFAULT false,  -- created after org deadline
  created_by_membership_id uuid NOT NULL,
  version                int NOT NULL DEFAULT 1,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  deleted_at             timestamptz,
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK ((attends AND arrival_time IS NOT NULL AND departure_time IS NOT NULL AND arrival_time < departure_time)
         OR (NOT attends AND arrival_time IS NULL AND departure_time IS NULL))
);
CREATE UNIQUE INDEX schedule_day_overrides_unique ON app.schedule_day_overrides (child_id, override_date) WHERE deleted_at IS NULL;
CREATE TRIGGER schedule_day_overrides_touch BEFORE UPDATE ON app.schedule_day_overrides FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- Non-working days for the whole organization or a single location.
CREATE TABLE app.closure_days (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  location_id      uuid,                               -- NULL = whole organization
  closure_date     date NOT NULL,
  name             text NOT NULL,
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (location_id, organization_id) REFERENCES app.locations(id, organization_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX closure_days_org_unique ON app.closure_days (organization_id, closure_date) WHERE location_id IS NULL;
CREATE UNIQUE INDEX closure_days_loc_unique ON app.closure_days (location_id, closure_date) WHERE location_id IS NOT NULL;

-- Frozen expectation per child per date. Materialised by the day-freeze job (and on demand for
-- future dates). Priority: closure > active absence > day override > template > none.
-- Historical rows are never recomputed after freeze => template changes are not retroactive.
CREATE TABLE app.daily_plans (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id     uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  child_id            uuid NOT NULL,
  group_id            uuid,                            -- group on that date (from enrollment); NULL if not enrolled
  plan_date           date NOT NULL,
  is_expected         boolean NOT NULL,
  expected_arrival    time,
  expected_departure  time,
  source              text NOT NULL CHECK (source IN ('CLOSURE','ABSENCE','OVERRIDE','TEMPLATE','NONE')),
  absence_id          uuid,                            -- when source = ABSENCE
  frozen_at           timestamptz,                     -- NULL while still recomputable (future date)
  computed_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (child_id, plan_date),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, organization_id) REFERENCES app.groups(id, organization_id),
  CHECK (NOT is_expected OR (expected_arrival IS NOT NULL AND expected_departure IS NOT NULL))
);
CREATE INDEX ON app.daily_plans (organization_id, group_id, plan_date);

-- Log of schedule changes (who changed what, was it late) — separate from generic audit_log because
-- staff need to query it as a business feed ("late changes today").
CREATE TABLE app.schedule_change_log (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id    uuid NOT NULL,
  child_id           uuid NOT NULL,
  change_kind        text NOT NULL CHECK (change_kind IN ('TEMPLATE_REPLACED','OVERRIDE_SET','OVERRIDE_REMOVED','WEEK_UPDATED')),
  affected_from      date NOT NULL,
  affected_to        date,
  is_late_change     boolean NOT NULL DEFAULT false,
  actor_membership_id uuid,
  before_state       jsonb,
  after_state        jsonb,
  occurred_at        timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE
);
CREATE INDEX ON app.schedule_change_log (organization_id, occurred_at DESC);
CREATE TRIGGER schedule_change_log_append_only BEFORE UPDATE OR DELETE ON app.schedule_change_log FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

-- =====================================================================================
--  6. ABSENCES
-- =====================================================================================

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

ALTER TABLE app.daily_plans
  ADD CONSTRAINT daily_plans_absence_fk FOREIGN KEY (absence_id, organization_id) REFERENCES app.absences(id, organization_id) ON DELETE SET NULL;

-- =====================================================================================
--  7. ATTENDANCE: IMMUTABLE EVENTS + PROJECTIONS (day, visits) + IDEMPOTENCY
-- =====================================================================================

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

-- Generic idempotency store for non-attendance commands (attendance uses attendance_events.command_id).
CREATE TABLE app.idempotency_keys (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid,                               -- NULL for global endpoints
  user_id          uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  scope            text NOT NULL,                      -- e.g. 'POST /absences'
  idempotency_key  text NOT NULL,
  request_hash     bytea NOT NULL,                     -- sha256 of canonical request body
  response_status  int,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz NOT NULL DEFAULT now() + interval '24 hours',
  UNIQUE (user_id, scope, idempotency_key)
);
CREATE INDEX ON app.idempotency_keys (expires_at);

-- =====================================================================================
--  8. ANNOUNCEMENTS, NOTIFICATIONS, DEVICES, OUTBOX
-- =====================================================================================

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

CREATE TABLE app.announcement_attachments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  announcement_id  uuid NOT NULL,
  file_id          uuid NOT NULL,                      -- FK to files added in section 9
  kind             text NOT NULL CHECK (kind IN ('IMAGE','ATTACHMENT')),
  sort_order       int NOT NULL DEFAULT 0,
  FOREIGN KEY (announcement_id, organization_id) REFERENCES app.announcements(id, organization_id) ON DELETE CASCADE,
  UNIQUE (announcement_id, file_id)
);

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

-- In-app inbox. organization_id NULL only for global/system notifications (e.g. security alerts).
CREATE TABLE app.notifications (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid REFERENCES app.organizations(id) ON DELETE CASCADE,
  recipient_user_id uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  membership_id    uuid,
  kind             text NOT NULL CHECK (kind IN ('ANNOUNCEMENT','SCHEDULE_CHANGE','ABSENCE','ATTENDANCE','CALENDAR_EVENT','URGENT','CONSENT_REQUEST','MESSAGE','SECURITY','SYSTEM')),
  title_key        text NOT NULL,                      -- i18n key; client renders in user locale
  title_args       jsonb NOT NULL DEFAULT '{}'::jsonb, -- never contains health data or message bodies
  ref_entity_type  text,
  ref_entity_id    uuid,
  dedup_key        text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  read_at          timestamptz,
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX notifications_dedup ON app.notifications (recipient_user_id, dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX ON app.notifications (recipient_user_id, created_at DESC);
CREATE INDEX ON app.notifications (recipient_user_id) WHERE read_at IS NULL;

CREATE TABLE app.device_push_tokens (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  session_id     uuid REFERENCES app.sessions(id) ON DELETE SET NULL,
  platform       text NOT NULL CHECK (platform IN ('ANDROID','IOS')),
  token          text NOT NULL,                        -- provider token (needed to send); rotate on invalidation
  app_version    text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  last_seen_at   timestamptz NOT NULL DEFAULT now(),
  invalidated_at timestamptz,
  invalidation_reason text,
  UNIQUE (platform, token)
);
CREATE INDEX ON app.device_push_tokens (user_id) WHERE invalidated_at IS NULL;

CREATE TABLE app.notification_deliveries (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id  uuid NOT NULL REFERENCES app.notifications(id) ON DELETE CASCADE,
  channel          text NOT NULL CHECK (channel IN ('PUSH','EMAIL')),
  device_token_id  uuid REFERENCES app.device_push_tokens(id) ON DELETE SET NULL,
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','DEAD')),
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text,
  sent_at          timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.notification_deliveries (status, next_attempt_at) WHERE status IN ('PENDING','FAILED');

-- Transactional outbox: written in the same transaction as the business change; relayed by a worker.
CREATE TABLE app.outbox_events (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id  uuid,
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  event_type       text NOT NULL,
  payload          jsonb NOT NULL,                     -- opaque ids only; no PII beyond ids
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSED','DEAD')),
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  processed_at     timestamptz
);
CREATE INDEX ON app.outbox_events (status, next_attempt_at, id) WHERE status = 'PENDING';

-- =====================================================================================
--  9. FILES, PHOTOS, CONSENT
-- =====================================================================================

-- Metadata only. Bytes live in private object storage under storage_key. No public URLs ever.
CREATE TABLE app.files (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id         uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  purpose                 text NOT NULL CHECK (purpose IN ('CHILD_PROFILE_PHOTO','GALLERY_PHOTO','ANNOUNCEMENT_IMAGE','ANNOUNCEMENT_ATTACHMENT','DOCUMENT','DATA_EXPORT')),
  bucket                  text NOT NULL,
  storage_key             text NOT NULL,               -- e.g. org/{org}/quarantine/{uuid}
  status                  text NOT NULL DEFAULT 'PENDING_UPLOAD' CHECK (status IN ('PENDING_UPLOAD','QUARANTINED','SCANNING','READY','REJECTED','DELETED')),
  declared_mime           text NOT NULL,
  detected_mime           text,
  size_bytes              bigint CHECK (size_bytes >= 0),
  sha256                  bytea,
  original_filename       text,                        -- sanitised; never used as storage key
  uploaded_by_membership_id uuid NOT NULL,
  rejection_reason        text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  ready_at                timestamptz,
  deleted_at              timestamptz,
  UNIQUE (id, organization_id),
  UNIQUE (bucket, storage_key),
  FOREIGN KEY (uploaded_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id)
);
CREATE INDEX ON app.files (organization_id, status);

ALTER TABLE app.children
  ADD CONSTRAINT children_photo_fk FOREIGN KEY (photo_file_id, organization_id) REFERENCES app.files(id, organization_id) ON DELETE SET NULL;
ALTER TABLE app.announcement_attachments
  ADD CONSTRAINT announcement_attachments_file_fk FOREIGN KEY (file_id, organization_id) REFERENCES app.files(id, organization_id);

-- Derived variants (thumbnails, web size). Same permissions as the original — always resolved via files.
CREATE TABLE app.file_variants (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  file_id       uuid NOT NULL,
  variant       text NOT NULL CHECK (variant IN ('THUMB_SM','THUMB_MD','WEB')),
  storage_key   text NOT NULL,
  width         int,
  height        int,
  size_bytes    bigint,
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (file_id, organization_id) REFERENCES app.files(id, organization_id) ON DELETE CASCADE,
  UNIQUE (file_id, variant)
);

CREATE TABLE app.photos (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id         uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  file_id                 uuid NOT NULL,
  caption                 text,
  taken_on                date,
  group_id                uuid,                        -- context group (optional)
  status                  text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
  uploaded_by_membership_id uuid NOT NULL,
  published_at            timestamptz,
  withdrawn_at            timestamptz,
  withdrawn_reason        text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  UNIQUE (file_id),
  FOREIGN KEY (file_id, organization_id)  REFERENCES app.files(id, organization_id),
  FOREIGN KEY (group_id, organization_id) REFERENCES app.groups(id, organization_id),
  FOREIGN KEY (uploaded_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id)
);
CREATE INDEX ON app.photos (organization_id, group_id, published_at DESC) WHERE status = 'PUBLISHED';
CREATE TRIGGER photos_touch BEFORE UPDATE ON app.photos FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

-- WHO may see the photo (audience) — separate from WHETHER it may be distributed (consent).
CREATE TABLE app.photo_audiences (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  photo_id         uuid NOT NULL,
  audience_type    text NOT NULL CHECK (audience_type IN ('GROUP','CHILD','GUARDIAN')),
  group_id         uuid,
  child_id         uuid,
  membership_id    uuid,
  FOREIGN KEY (photo_id, organization_id)      REFERENCES app.photos(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, organization_id)      REFERENCES app.groups(id, organization_id),
  FOREIGN KEY (child_id, organization_id)      REFERENCES app.children(id, organization_id),
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  CHECK (
    (audience_type = 'GROUP'    AND group_id IS NOT NULL AND child_id IS NULL AND membership_id IS NULL) OR
    (audience_type = 'CHILD'    AND child_id IS NOT NULL AND group_id IS NULL AND membership_id IS NULL) OR
    (audience_type = 'GUARDIAN' AND membership_id IS NOT NULL AND group_id IS NULL AND child_id IS NULL)
  )
);
CREATE INDEX ON app.photo_audiences (photo_id);

-- Staff must tag every recognisable child. Publishing requires valid consent for ALL tagged children.
CREATE TABLE app.photo_tagged_children (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id       uuid NOT NULL,
  photo_id              uuid NOT NULL,
  child_id              uuid NOT NULL,
  tagged_by_membership_id uuid NOT NULL,
  tagged_at             timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (photo_id, organization_id) REFERENCES app.photos(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (tagged_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  UNIQUE (photo_id, child_id)
);
CREATE INDEX ON app.photo_tagged_children (child_id);

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

-- One decision row per (child, policy, guardian) event. Current state = latest non-withdrawn GRANTED
-- for the child's purpose on a policy version that is not retired.
CREATE TABLE app.consent_decisions (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id        uuid NOT NULL,
  child_id               uuid NOT NULL,
  policy_id              uuid NOT NULL,
  guardian_id            uuid NOT NULL,                -- guardians.id (confirmed link with can_give_consent)
  decision               text NOT NULL CHECK (decision IN ('GRANTED','DECLINED')),
  decided_at             timestamptz NOT NULL DEFAULT now(),
  withdrawn_at           timestamptz,
  withdrawal_reason      text,
  evidence               jsonb NOT NULL DEFAULT '{}'::jsonb,  -- {ip, userAgent, sessionId, wordingHash}
  created_at             timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (child_id, organization_id)  REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (policy_id, organization_id) REFERENCES app.consent_policies(id, organization_id),
  FOREIGN KEY (guardian_id, organization_id) REFERENCES app.guardians(id, organization_id)
);
CREATE INDEX ON app.consent_decisions (child_id, policy_id, decided_at DESC);

-- =====================================================================================
--  10. MESSAGING (P1)
-- =====================================================================================

CREATE TABLE app.conversations (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  kind             text NOT NULL CHECK (kind IN ('PARENT_TEACHER','PARENT_ADMIN')),
  child_id         uuid NOT NULL,                      -- every conversation is about one child
  group_id         uuid,                               -- for PARENT_TEACHER: the child's group at creation
  subject          text,
  created_by_membership_id uuid NOT NULL,
  last_message_at  timestamptz,
  closed_at        timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (child_id, organization_id) REFERENCES app.children(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, organization_id) REFERENCES app.groups(id, organization_id),
  FOREIGN KEY (created_by_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id)
);
CREATE INDEX ON app.conversations (organization_id, child_id, last_message_at DESC);

-- Participants are managed by the server from guardian links and teacher assignments; never client-chosen.
CREATE TABLE app.conversation_participants (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id       uuid NOT NULL,
  conversation_id       uuid NOT NULL,
  membership_id         uuid NOT NULL,
  participant_role      text NOT NULL CHECK (participant_role IN ('GUARDIAN','TEACHER','ADMIN')),
  joined_at             timestamptz NOT NULL DEFAULT now(),
  left_at               timestamptz,                   -- set when guardian/teacher rights end
  last_read_message_id  uuid,
  last_read_at          timestamptz,
  FOREIGN KEY (conversation_id, organization_id) REFERENCES app.conversations(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (membership_id, organization_id)   REFERENCES app.organization_memberships(id, organization_id) ON DELETE CASCADE,
  UNIQUE (conversation_id, membership_id)
);
CREATE INDEX ON app.conversation_participants (membership_id) WHERE left_at IS NULL;

CREATE TABLE app.messages (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id       uuid NOT NULL,
  conversation_id       uuid NOT NULL,
  sender_membership_id  uuid NOT NULL,
  client_message_id     uuid NOT NULL,                 -- idempotent resend
  body                  text NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
  created_at            timestamptz NOT NULL DEFAULT now(),
  edited_at             timestamptz,
  deleted_at            timestamptz,
  FOREIGN KEY (conversation_id, organization_id)     REFERENCES app.conversations(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (sender_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  UNIQUE (conversation_id, client_message_id)
);
CREATE INDEX ON app.messages (conversation_id, created_at DESC);

ALTER TABLE app.conversation_participants
  ADD CONSTRAINT conversation_participants_last_read_fk FOREIGN KEY (last_read_message_id) REFERENCES app.messages(id) ON DELETE SET NULL;

-- =====================================================================================
--  11. CALENDAR & MEALS (P1)
-- =====================================================================================

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

-- =====================================================================================
--  12. DOCUMENTS & DIGITAL RESPONSES (P2 — modelled, not implemented)
-- =====================================================================================

CREATE TABLE app.documents (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  kind             text NOT NULL CHECK (kind IN ('CONSENT_FORM','CONTRACT','POLICY','OTHER')),
  title            text NOT NULL,
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  archived_at      timestamptz,
  UNIQUE (id, organization_id)
);

CREATE TABLE app.document_versions (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  document_id      uuid NOT NULL,
  version_no       int NOT NULL CHECK (version_no >= 1),
  file_id          uuid NOT NULL,
  content_hash     bytea NOT NULL,                     -- sha256 of file bytes at publish
  published_at     timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  UNIQUE (document_id, version_no),
  FOREIGN KEY (document_id, organization_id) REFERENCES app.documents(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (file_id, organization_id)     REFERENCES app.files(id, organization_id)
);

CREATE TABLE app.document_requests (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id      uuid NOT NULL,
  document_version_id  uuid NOT NULL,
  child_id             uuid,                           -- NULL for staff/general documents
  target_membership_id uuid NOT NULL,
  due_at               timestamptz,
  status               text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESPONDED','EXPIRED','CANCELLED')),
  created_by           uuid REFERENCES app.users(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  FOREIGN KEY (document_version_id, organization_id) REFERENCES app.document_versions(id, organization_id),
  FOREIGN KEY (child_id, organization_id)            REFERENCES app.children(id, organization_id),
  FOREIGN KEY (target_membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id)
);

-- A click-through acceptance with evidence. NOT a qualified electronic signature.
CREATE TABLE app.document_responses (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  request_id       uuid NOT NULL,
  membership_id    uuid NOT NULL,
  decision         text NOT NULL CHECK (decision IN ('ACCEPT','DECLINE')),
  content_hash     bytea NOT NULL,                     -- hash of the version actually shown
  responded_at     timestamptz NOT NULL DEFAULT now(),
  evidence         jsonb NOT NULL DEFAULT '{}'::jsonb,
  FOREIGN KEY (request_id, organization_id)    REFERENCES app.document_requests(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (membership_id, organization_id) REFERENCES app.organization_memberships(id, organization_id),
  UNIQUE (request_id)
);
CREATE TRIGGER document_responses_append_only BEFORE UPDATE OR DELETE ON app.document_responses FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

-- =====================================================================================
--  13. BILLING, PLANS, FEATURE FLAGS
-- =====================================================================================

-- Global catalogue (platform-managed). Versioned so price changes never rewrite history.
CREATE TABLE app.plans (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                 text NOT NULL CHECK (code IN ('STARTER','STANDARD','PRO')),
  version              int  NOT NULL CHECK (version >= 1),
  name                 text NOT NULL,
  currency             char(3) NOT NULL DEFAULT 'RSD',
  monthly_price_minor  bigint NOT NULL CHECK (monthly_price_minor >= 0),   -- minor units, never float
  entitlements         jsonb NOT NULL DEFAULT '{}'::jsonb,               -- {"photos_enabled":true,...}
  limits               jsonb NOT NULL DEFAULT '{}'::jsonb,               -- {"max_children":60,"max_locations":1}
  is_active            boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (code, version)
);

CREATE TABLE app.subscriptions (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id       uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  plan_id               uuid NOT NULL REFERENCES app.plans(id),
  status                text NOT NULL CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','CANCELLED')),
  trial_ends_at         timestamptz,
  current_period_start  timestamptz NOT NULL,
  current_period_end    timestamptz NOT NULL,
  cancelled_at          timestamptz,
  cancel_at_period_end  boolean NOT NULL DEFAULT false,
  provider              text NOT NULL DEFAULT 'MANUAL' CHECK (provider IN ('MANUAL','STRIPE','LOCAL_INVOICE')),
  provider_ref          text,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, organization_id),
  CHECK (current_period_end > current_period_start)
);
CREATE UNIQUE INDEX subscriptions_one_current ON app.subscriptions (organization_id) WHERE status IN ('TRIAL','ACTIVE','PAST_DUE');
CREATE TRIGGER subscriptions_touch BEFORE UPDATE ON app.subscriptions FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.subscription_events (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id  uuid NOT NULL,
  subscription_id  uuid NOT NULL,
  event_type       text NOT NULL,                      -- CREATED, PLAN_CHANGED, PAYMENT_FAILED, ...
  payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (subscription_id, organization_id) REFERENCES app.subscriptions(id, organization_id) ON DELETE CASCADE
);
CREATE TRIGGER subscription_events_append_only BEFORE UPDATE OR DELETE ON app.subscription_events FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

-- Global flag registry with emergency kill switch. Effective = NOT kill_switch AND (tenant override ?? plan entitlement ?? default)
CREATE TABLE app.feature_flags (
  key              text PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_]{2,63}$'),
  description      text NOT NULL,
  default_enabled  boolean NOT NULL DEFAULT false,
  kill_switch      boolean NOT NULL DEFAULT false,
  updated_by       uuid REFERENCES app.users(id),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.organization_feature_overrides (
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  flag_key         text NOT NULL REFERENCES app.feature_flags(key) ON DELETE CASCADE,
  enabled          boolean NOT NULL,
  reason           text,
  set_by           uuid REFERENCES app.users(id),
  expires_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, flag_key)
);

-- =====================================================================================
--  14. AUDIT, PRIVACY, RETENTION
-- =====================================================================================

-- Append-only. Runtime role has INSERT + SELECT only; trigger blocks UPDATE/DELETE.
-- NOTE: this protects against application bugs and ordinary users, NOT against a privileged DBA.
-- Tamper-evidence for privileged actors requires shipping to write-once external storage (see SECURITY.md).
CREATE TABLE app.audit_log (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at         timestamptz NOT NULL DEFAULT now(),
  actor_user_id       uuid,
  actor_membership_id uuid,
  organization_id     uuid,                            -- NULL for global/platform actions
  action              text NOT NULL,                   -- e.g. CHILD_UPDATED, GUARDIAN_REVOKED, HEALTH_READ
  entity_type         text NOT NULL,
  entity_id           uuid,
  request_id          text,
  result              text NOT NULL CHECK (result IN ('SUCCESS','DENIED','FAILED')),
  purpose             text,                            -- required for HEALTH_READ, SUPPORT_ACCESS, EXPORT
  metadata            jsonb NOT NULL DEFAULT '{}'::jsonb   -- allowlisted keys only; never payloads/PII
);
CREATE INDEX ON app.audit_log (organization_id, occurred_at DESC);
CREATE INDEX ON app.audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ON app.audit_log (actor_user_id, occurred_at DESC);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

CREATE TABLE app.privacy_requests (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id       uuid REFERENCES app.organizations(id) ON DELETE SET NULL,
  requester_user_id     uuid NOT NULL REFERENCES app.users(id),
  subject_type          text NOT NULL CHECK (subject_type IN ('USER','CHILD')),
  subject_id            uuid NOT NULL,
  kind                  text NOT NULL CHECK (kind IN ('ACCESS','EXPORT','ERASURE','RECTIFICATION')),
  status                text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','IDENTITY_PENDING','IN_PROGRESS','COMPLETED','REJECTED')),
  identity_verified_at  timestamptz,
  identity_verified_by  uuid REFERENCES app.users(id),
  rejection_reason      text,
  completed_at          timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER privacy_requests_touch BEFORE UPDATE ON app.privacy_requests FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.data_exports (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id     uuid,
  privacy_request_id  uuid NOT NULL REFERENCES app.privacy_requests(id) ON DELETE CASCADE,
  file_id             uuid,                            -- files.purpose = DATA_EXPORT (private, short-lived)
  scope_description   text NOT NULL,                   -- what was included (never another child / other guardian's private data)
  expires_at          timestamptz NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now()
);

-- Ledger of completed erasures, re-applied after any backup restore.
CREATE TABLE app.erasure_ledger (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id   uuid,
  subject_type      text NOT NULL CHECK (subject_type IN ('USER','CHILD','ORGANIZATION')),
  subject_id        uuid NOT NULL,                     -- the id is kept so restore can re-erase; personal data is not
  erased_at         timestamptz NOT NULL DEFAULT now(),
  scope             jsonb NOT NULL DEFAULT '{}'::jsonb,
  reapplied_at      timestamptz
);
CREATE TRIGGER erasure_ledger_append_only BEFORE UPDATE OR DELETE ON app.erasure_ledger FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

-- Retention periods are PROPOSALS to be confirmed with legal counsel; NULL organization = platform default.
CREATE TABLE app.retention_policies (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid REFERENCES app.organizations(id) ON DELETE CASCADE,
  entity_type      text NOT NULL,                      -- ATTENDANCE, MESSAGES, PHOTOS, AUDIT, SESSIONS, ...
  retention_days   int NOT NULL CHECK (retention_days > 0),
  basis_note       text,
  updated_by       uuid REFERENCES app.users(id),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX retention_policies_default ON app.retention_policies (entity_type) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX retention_policies_org     ON app.retention_policies (organization_id, entity_type) WHERE organization_id IS NOT NULL;

CREATE TABLE app.legal_holds (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  entity_type      text NOT NULL,
  entity_id        uuid NOT NULL,
  reason           text NOT NULL,
  placed_by        uuid NOT NULL REFERENCES app.users(id),
  placed_at        timestamptz NOT NULL DEFAULT now(),
  released_by      uuid REFERENCES app.users(id),
  released_at      timestamptz
);
CREATE INDEX ON app.legal_holds (entity_type, entity_id) WHERE released_at IS NULL;

-- =====================================================================================
--  15. ROW LEVEL SECURITY
-- =====================================================================================
--  Tenant tables: strict equality with app.current_organization_id(). NULL context => no rows.
--  Global tables: explicit policies below.
-- -------------------------------------------------------------------------------------

DO $$
DECLARE
  t text;
  tenant_tables text[] := ARRAY[
    'organization_settings','organization_memberships','membership_permissions','invitations','support_access_grants',
    'locations','groups','employees','group_teacher_assignments',
    'children','enrollments','guardians','pickup_persons','child_health_profiles',
    'schedule_templates','schedule_template_days','schedule_day_overrides','closure_days','daily_plans','schedule_change_log',
    'absences',
    'attendance_days','attendance_visits','attendance_events',
    'announcements','announcement_audiences','announcement_attachments','announcement_recipients',
    'files','file_variants','photos','photo_audiences','photo_tagged_children','consent_policies','consent_decisions',
    'conversations','conversation_participants','messages',
    'calendar_events','menu_days','menu_items',
    'documents','document_versions','document_requests','document_responses',
    'subscriptions','subscription_events','organization_feature_overrides','legal_holds'
  ];
BEGIN
  FOREACH t IN ARRAY tenant_tables LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I FOR ALL TO app_runtime
         USING (organization_id = app.current_organization_id())
         WITH CHECK (organization_id = app.current_organization_id())', t);
  END LOOP;
END $$;

-- organization_memberships: a user may additionally read their OWN memberships across tenants (tenant switcher).
CREATE POLICY self_memberships ON app.organization_memberships FOR SELECT TO app_runtime
  USING (user_id = app.current_user_id());

-- organizations: current tenant, or any org the current user is an active member of (switcher), or platform mode.
ALTER TABLE app.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY organizations_access ON app.organizations FOR SELECT TO app_runtime
  USING (
    id = app.current_organization_id()
    OR app.platform_mode()
    OR EXISTS (SELECT 1 FROM app.organization_memberships m
               WHERE m.organization_id = organizations.id AND m.user_id = app.current_user_id() AND m.status = 'ACTIVE')
  );
CREATE POLICY organizations_write_tenant ON app.organizations FOR UPDATE TO app_runtime
  USING (id = app.current_organization_id() OR app.platform_mode())
  WITH CHECK (id = app.current_organization_id() OR app.platform_mode());
CREATE POLICY organizations_insert_platform ON app.organizations FOR INSERT TO app_runtime
  WITH CHECK (app.platform_mode());

-- users: self, auth module, platform mode, or a fellow member of the current tenant (names for lists).
ALTER TABLE app.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_access ON app.users FOR SELECT TO app_runtime
  USING (
    id = app.current_user_id()
    OR app.auth_mode()
    OR app.platform_mode()
    OR EXISTS (SELECT 1 FROM app.organization_memberships m
               WHERE m.user_id = users.id AND m.organization_id = app.current_organization_id())
  );
CREATE POLICY users_self_update ON app.users FOR UPDATE TO app_runtime
  USING (id = app.current_user_id() OR app.auth_mode() OR app.platform_mode())
  WITH CHECK (id = app.current_user_id() OR app.auth_mode() OR app.platform_mode());
CREATE POLICY users_insert_auth ON app.users FOR INSERT TO app_runtime
  WITH CHECK (app.auth_mode() OR app.platform_mode());

-- auth tables: auth module only, except the user may list/revoke their own sessions.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['email_verification_tokens','password_reset_tokens','refresh_tokens','access_tokens','user_mfa_methods','user_mfa_recovery_codes','login_attempts','platform_admins'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY auth_only ON app.%I FOR ALL TO app_runtime USING (app.auth_mode()) WITH CHECK (app.auth_mode())', t);
  END LOOP;
END $$;
ALTER TABLE app.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY sessions_auth ON app.sessions FOR ALL TO app_runtime USING (app.auth_mode()) WITH CHECK (app.auth_mode());
CREATE POLICY sessions_self ON app.sessions FOR SELECT TO app_runtime USING (user_id = app.current_user_id());
CREATE POLICY sessions_self_revoke ON app.sessions FOR UPDATE TO app_runtime USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id());

-- notifications / device tokens: recipient only.
ALTER TABLE app.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY notifications_recipient ON app.notifications FOR ALL TO app_runtime
  USING (recipient_user_id = app.current_user_id())
  WITH CHECK (recipient_user_id = app.current_user_id() OR organization_id = app.current_organization_id());
ALTER TABLE app.device_push_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.device_push_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY device_tokens_owner ON app.device_push_tokens FOR ALL TO app_runtime
  USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id());

-- notification_deliveries / outbox_events / idempotency_keys / privacy tables: worker-mode or scoped.
ALTER TABLE app.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_owner ON app.idempotency_keys FOR ALL TO app_runtime
  USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id());

-- audit_log: insert always allowed for runtime; read limited to own tenant or platform mode.
ALTER TABLE app.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_insert ON app.audit_log FOR INSERT TO app_runtime WITH CHECK (true);
CREATE POLICY audit_read ON app.audit_log FOR SELECT TO app_runtime
  USING (organization_id = app.current_organization_id() OR app.platform_mode());

-- privacy_requests / data_exports / erasure_ledger / retention_policies: tenant or platform.
ALTER TABLE app.privacy_requests ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.privacy_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY privacy_requests_access ON app.privacy_requests FOR ALL TO app_runtime
  USING (organization_id = app.current_organization_id() OR requester_user_id = app.current_user_id() OR app.platform_mode())
  WITH CHECK (organization_id = app.current_organization_id() OR requester_user_id = app.current_user_id() OR app.platform_mode());
ALTER TABLE app.data_exports ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.data_exports FORCE ROW LEVEL SECURITY;
CREATE POLICY data_exports_access ON app.data_exports FOR ALL TO app_runtime
  USING (organization_id = app.current_organization_id() OR app.platform_mode())
  WITH CHECK (organization_id = app.current_organization_id() OR app.platform_mode());
ALTER TABLE app.erasure_ledger ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.erasure_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY erasure_ledger_access ON app.erasure_ledger FOR ALL TO app_runtime
  USING (organization_id = app.current_organization_id() OR app.platform_mode())
  WITH CHECK (organization_id = app.current_organization_id() OR app.platform_mode());
ALTER TABLE app.retention_policies ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.retention_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY retention_read ON app.retention_policies FOR SELECT TO app_runtime
  USING (organization_id IS NULL OR organization_id = app.current_organization_id() OR app.platform_mode());
CREATE POLICY retention_write ON app.retention_policies FOR ALL TO app_runtime
  USING (organization_id = app.current_organization_id() OR app.platform_mode())
  WITH CHECK (organization_id = app.current_organization_id() OR app.platform_mode());

-- Global catalogues readable by everyone authenticated; writable only in platform mode.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['plans','feature_flags'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY read_all ON app.%I FOR SELECT TO app_runtime USING (true)', t);
    EXECUTE format('CREATE POLICY platform_write ON app.%I FOR ALL TO app_runtime USING (app.platform_mode()) WITH CHECK (app.platform_mode())', t);
  END LOOP;
END $$;

-- Worker tables (outbox, deliveries): the relay worker runs as app_worker (see roles script);
-- app_runtime may only INSERT outbox rows in the same transaction as business changes.
ALTER TABLE app.outbox_events ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_insert_runtime ON app.outbox_events FOR INSERT TO app_runtime WITH CHECK (true);
CREATE POLICY outbox_worker ON app.outbox_events FOR ALL TO app_worker USING (true) WITH CHECK (true);
ALTER TABLE app.notification_deliveries ENABLE ROW LEVEL SECURITY;  ALTER TABLE app.notification_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY deliveries_worker ON app.notification_deliveries FOR ALL TO app_worker USING (true) WITH CHECK (true);
-- The worker needs to read notifications/device tokens/users(email) regardless of user context:
CREATE POLICY notifications_worker   ON app.notifications      FOR ALL TO app_worker USING (true) WITH CHECK (true);
CREATE POLICY device_tokens_worker   ON app.device_push_tokens FOR ALL TO app_worker USING (true) WITH CHECK (true);
CREATE POLICY users_worker_read      ON app.users              FOR SELECT TO app_worker USING (true);
CREATE POLICY memberships_worker_read ON app.organization_memberships FOR SELECT TO app_worker USING (true);

-- Owner maintenance policy on EVERY RLS-protected table (opt-in per transaction via app.maintenance_mode()).
-- Without it the owner (FORCE RLS) sees no tenant rows at all — proven by RlsIntegrationTest.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'app' AND c.relkind = 'r' AND c.relrowsecurity LOOP
    EXECUTE format('CREATE POLICY owner_maintenance ON app.%I FOR ALL TO app_owner USING (app.maintenance_mode()) WITH CHECK (app.maintenance_mode())', r.relname);
  END LOOP;
END $$;

-- =====================================================================================
--  16. PRIVILEGES (owner creates everything; runtime/worker get least privilege)
-- =====================================================================================
GRANT USAGE ON SCHEMA app TO app_runtime, app_worker;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA app TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA app TO app_worker;
-- Hard deletes are allowed only where the model uses them deliberately (tokens, idempotency, device tokens, audience rules).
GRANT DELETE ON app.email_verification_tokens, app.password_reset_tokens, app.access_tokens, app.refresh_tokens,
                app.idempotency_keys, app.device_push_tokens, app.announcement_audiences, app.photo_audiences,
                app.schedule_template_days, app.menu_items, app.user_mfa_recovery_codes TO app_runtime;
GRANT DELETE ON app.outbox_events, app.notification_deliveries TO app_worker;
-- Append-only tables: the trigger is the last line; the privilege model is the first.
REVOKE UPDATE ON app.audit_log, app.subscription_events, app.schedule_change_log, app.attendance_events,
                 app.erasure_ledger, app.document_responses FROM app_runtime, app_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO app_runtime, app_worker;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT SELECT, INSERT, UPDATE ON TABLES TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT USAGE, SELECT ON SEQUENCES TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT EXECUTE ON FUNCTIONS TO app_runtime, app_worker;
