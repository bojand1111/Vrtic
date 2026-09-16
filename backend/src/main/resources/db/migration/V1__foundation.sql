-- =====================================================================================
--  V1 — FOUNDATION (EPIC 01)
--  Executable subset of docs/database/schema.sql: identity & auth, organizations, memberships,
--  locations/groups/employees, audit, outbox, idempotency, plans/subscriptions/feature flags,
--  RLS policies and least-privilege grants.
--  Runs as app_owner (Flyway). Roles app_owner / app_runtime / app_worker must already exist
--  (scripts/db/00_roles.sql). Extensions are created by the superuser in that script.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS app;
SET search_path TO app, public;

-- ---------------------------------------------------------------- context functions
CREATE OR REPLACE FUNCTION app.current_organization_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT NULLIF(current_setting('app.organization_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app.current_user_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app.auth_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.auth_mode', true), '') = 'on'
$$;

CREATE OR REPLACE FUNCTION app.platform_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.platform_mode', true), '') = 'on'
$$;

-- Owner-only escape hatch for migrations/seeds/data fixes. FORCE RLS binds the owner too,
-- so the owner must opt in explicitly per transaction: set_config('app.maintenance_mode','on',true).
CREATE OR REPLACE FUNCTION app.maintenance_mode() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT COALESCE(current_setting('app.maintenance_mode', true), '') = 'on'
$$;

CREATE OR REPLACE FUNCTION app.touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION app.deny_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;

-- ---------------------------------------------------------------- 1. identity & auth
CREATE TABLE app.users (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email               citext NOT NULL,
  email_verified_at   timestamptz,
  password_hash       text,
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
  token_hash   bytea NOT NULL UNIQUE,
  new_email    citext,
  expires_at   timestamptz NOT NULL,
  consumed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.email_verification_tokens (user_id);

CREATE TABLE app.password_reset_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  token_hash   bytea NOT NULL UNIQUE,
  expires_at   timestamptz NOT NULL,
  consumed_at  timestamptz,
  requested_ip inet,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.password_reset_tokens (user_id);

CREATE TABLE app.sessions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  client_kind          text NOT NULL CHECK (client_kind IN ('WEB','ANDROID','IOS')),
  device_name          text,
  device_id            text,
  user_agent           text,
  ip_created           inet,
  mfa_verified_at      timestamptz,
  reauthenticated_at   timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  last_seen_at         timestamptz NOT NULL DEFAULT now(),
  absolute_expires_at  timestamptz NOT NULL,
  revoked_at           timestamptz,
  revoke_reason        text CHECK (revoke_reason IN ('USER_LOGOUT','LOGOUT_ALL','ADMIN','REUSE_DETECTED','EXPIRED','PASSWORD_CHANGED'))
);
CREATE INDEX ON app.sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE app.refresh_tokens (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id        uuid NOT NULL REFERENCES app.sessions(id) ON DELETE CASCADE,
  token_hash        bytea NOT NULL UNIQUE,
  issued_at         timestamptz NOT NULL DEFAULT now(),
  idle_expires_at   timestamptz NOT NULL,
  consumed_at       timestamptz,
  replaced_by_id    uuid REFERENCES app.refresh_tokens(id),
  reuse_detected_at timestamptz
);
CREATE UNIQUE INDEX refresh_tokens_one_active_per_session ON app.refresh_tokens (session_id) WHERE consumed_at IS NULL;

CREATE TABLE app.access_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id  uuid NOT NULL REFERENCES app.sessions(id) ON DELETE CASCADE,
  token_hash  bytea NOT NULL UNIQUE,
  issued_at   timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);
CREATE INDEX ON app.access_tokens (session_id);
CREATE INDEX ON app.access_tokens (expires_at);

CREATE TABLE app.user_mfa_methods (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  type        text NOT NULL CHECK (type IN ('TOTP')),
  secret_enc  bytea NOT NULL,
  key_id      text  NOT NULL,
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

CREATE TABLE app.login_attempts (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email_hash   bytea NOT NULL,
  ip           inet,
  succeeded    boolean NOT NULL,
  occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.login_attempts (email_hash, occurred_at DESC);
CREATE INDEX ON app.login_attempts (ip, occurred_at DESC);

CREATE TABLE app.platform_admins (
  user_id     uuid PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
  granted_by  uuid REFERENCES app.users(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at  timestamptz,
  note        text
);

-- ---------------------------------------------------------------- 2. organizations & memberships
CREATE TABLE app.organizations (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug            citext NOT NULL UNIQUE,
  name            text NOT NULL,
  legal_name      text,
  country_code    char(2) NOT NULL DEFAULT 'RS',
  timezone        text NOT NULL DEFAULT 'Europe/Belgrade',
  default_locale  text NOT NULL DEFAULT 'sr-Latn' CHECK (default_locale IN ('sr-Latn','sr-Cyrl','en')),
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  created_by      uuid REFERENCES app.users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz
);
CREATE TRIGGER organizations_touch BEFORE UPDATE ON app.organizations FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

CREATE TABLE app.organization_settings (
  organization_id                 uuid PRIMARY KEY REFERENCES app.organizations(id) ON DELETE CASCADE,
  schedule_change_deadline_hours  int  NOT NULL DEFAULT 12 CHECK (schedule_change_deadline_hours BETWEEN 0 AND 168),
  late_arrival_grace_minutes      int  NOT NULL DEFAULT 15 CHECK (late_arrival_grace_minutes BETWEEN 0 AND 180),
  day_opens_at                    time NOT NULL DEFAULT '06:00',
  day_closes_at                   time NOT NULL DEFAULT '18:00',
  working_weekdays                smallint[] NOT NULL DEFAULT '{1,2,3,4,5}',
  offline_cache_ttl_hours         int NOT NULL DEFAULT 12 CHECK (offline_cache_ttl_hours BETWEEN 1 AND 48),
  updated_at                      timestamptz NOT NULL DEFAULT now(),
  CHECK (day_opens_at < day_closes_at)
);
CREATE TRIGGER organization_settings_touch BEFORE UPDATE ON app.organization_settings FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

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

CREATE TABLE app.invitations (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  email            citext NOT NULL,
  role             text NOT NULL CHECK (role IN ('OWNER','ADMIN','TEACHER','PARENT')),
  child_id         uuid,                               -- FK to children added by the EPIC 05 migration
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

CREATE TABLE app.support_access_grants (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id         uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  support_user_id         uuid NOT NULL REFERENCES app.users(id),
  reason                  text NOT NULL,
  ticket_ref              text NOT NULL,
  scope                   text[] NOT NULL,
  read_only               boolean NOT NULL DEFAULT true,
  approval_kind           text NOT NULL CHECK (approval_kind IN ('OWNER_APPROVED','EMERGENCY')),
  approved_by             uuid REFERENCES app.users(id),
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

-- ---------------------------------------------------------------- 3. structure
CREATE TABLE app.locations (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  name             text NOT NULL,
  address_line     text,
  city             text,
  postal_code      text,
  country_code     char(2) NOT NULL DEFAULT 'RS',
  timezone         text,
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

CREATE TABLE app.group_teacher_assignments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL REFERENCES app.organizations(id) ON DELETE CASCADE,
  group_id         uuid NOT NULL,
  employee_id      uuid NOT NULL,
  assignment_role  text NOT NULL DEFAULT 'LEAD' CHECK (assignment_role IN ('LEAD','ASSISTANT','SUBSTITUTE')),
  valid_from       date NOT NULL,
  valid_to         date,
  created_by       uuid REFERENCES app.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  revoked_at       timestamptz,
  FOREIGN KEY (group_id, organization_id)    REFERENCES app.groups(id, organization_id) ON DELETE CASCADE,
  FOREIGN KEY (employee_id, organization_id) REFERENCES app.employees(id, organization_id) ON DELETE CASCADE,
  CHECK (valid_to IS NULL OR valid_to >= valid_from),
  EXCLUDE USING gist (
    employee_id WITH =, group_id WITH =,
    daterange(valid_from, valid_to, '[]') WITH &&
  ) WHERE (revoked_at IS NULL)
);
CREATE INDEX ON app.group_teacher_assignments (organization_id, employee_id) WHERE revoked_at IS NULL;
CREATE INDEX ON app.group_teacher_assignments (organization_id, group_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------- 4. cross-cutting: idempotency, outbox, audit
CREATE TABLE app.idempotency_keys (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid,
  user_id          uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  scope            text NOT NULL,
  idempotency_key  text NOT NULL,
  request_hash     bytea NOT NULL,
  response_status  int,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz NOT NULL DEFAULT now() + interval '24 hours',
  UNIQUE (user_id, scope, idempotency_key)
);
CREATE INDEX ON app.idempotency_keys (expires_at);

CREATE TABLE app.outbox_events (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id  uuid,
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  event_type       text NOT NULL,
  payload          jsonb NOT NULL,
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSED','DEAD')),
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  processed_at     timestamptz
);
CREATE INDEX ON app.outbox_events (status, next_attempt_at, id) WHERE status = 'PENDING';

CREATE TABLE app.audit_log (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at         timestamptz NOT NULL DEFAULT now(),
  actor_user_id       uuid,
  actor_membership_id uuid,
  organization_id     uuid,
  action              text NOT NULL,
  entity_type         text NOT NULL,
  entity_id           uuid,
  request_id          text,
  result              text NOT NULL CHECK (result IN ('SUCCESS','DENIED','FAILED')),
  purpose             text,
  metadata            jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ON app.audit_log (organization_id, occurred_at DESC);
CREATE INDEX ON app.audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ON app.audit_log (actor_user_id, occurred_at DESC);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

-- ---------------------------------------------------------------- 5. plans, subscriptions, feature flags
CREATE TABLE app.plans (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                 text NOT NULL CHECK (code IN ('STARTER','STANDARD','PRO')),
  version              int  NOT NULL CHECK (version >= 1),
  name                 text NOT NULL,
  currency             char(3) NOT NULL DEFAULT 'RSD',
  monthly_price_minor  bigint NOT NULL CHECK (monthly_price_minor >= 0),
  entitlements         jsonb NOT NULL DEFAULT '{}'::jsonb,
  limits               jsonb NOT NULL DEFAULT '{}'::jsonb,
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
  event_type       text NOT NULL,
  payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (subscription_id, organization_id) REFERENCES app.subscriptions(id, organization_id) ON DELETE CASCADE
);
CREATE TRIGGER subscription_events_append_only BEFORE UPDATE OR DELETE ON app.subscription_events FOR EACH ROW EXECUTE FUNCTION app.deny_modification();

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

-- ---------------------------------------------------------------- 6. ROW LEVEL SECURITY
DO $$
DECLARE
  t text;
  tenant_tables text[] := ARRAY[
    'organization_settings','organization_memberships','membership_permissions','invitations','support_access_grants',
    'locations','groups','employees','group_teacher_assignments',
    'subscriptions','subscription_events','organization_feature_overrides'
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

CREATE POLICY self_memberships ON app.organization_memberships FOR SELECT TO app_runtime
  USING (user_id = app.current_user_id());

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

ALTER TABLE app.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_owner ON app.idempotency_keys FOR ALL TO app_runtime
  USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id());

ALTER TABLE app.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_insert ON app.audit_log FOR INSERT TO app_runtime WITH CHECK (true);
CREATE POLICY audit_read ON app.audit_log FOR SELECT TO app_runtime
  USING (organization_id = app.current_organization_id() OR app.platform_mode());

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

ALTER TABLE app.outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_insert_runtime ON app.outbox_events FOR INSERT TO app_runtime WITH CHECK (true);
CREATE POLICY outbox_worker ON app.outbox_events FOR ALL TO app_worker USING (true) WITH CHECK (true);
CREATE POLICY users_worker_read ON app.users FOR SELECT TO app_worker USING (true);
CREATE POLICY memberships_worker_read ON app.organization_memberships FOR SELECT TO app_worker USING (true);

-- Owner maintenance policy on EVERY RLS-protected table (opt-in per transaction, see app.maintenance_mode()).
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'app' AND c.relkind = 'r' AND c.relrowsecurity LOOP
    EXECUTE format('CREATE POLICY owner_maintenance ON app.%I FOR ALL TO app_owner USING (app.maintenance_mode()) WITH CHECK (app.maintenance_mode())', r.relname);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- 7. privileges
GRANT USAGE ON SCHEMA app TO app_runtime, app_worker;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA app TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA app TO app_worker;
GRANT DELETE ON app.email_verification_tokens, app.password_reset_tokens, app.access_tokens, app.refresh_tokens,
                app.idempotency_keys, app.user_mfa_recovery_codes TO app_runtime;
GRANT DELETE ON app.outbox_events TO app_worker;
-- Append-only tables: the trigger is the last line; the privilege model is the first.
REVOKE UPDATE ON app.audit_log, app.subscription_events FROM app_runtime, app_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO app_runtime, app_worker;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT SELECT, INSERT, UPDATE ON TABLES TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT USAGE, SELECT ON SEQUENCES TO app_runtime, app_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT EXECUTE ON FUNCTIONS TO app_runtime, app_worker;
-- Readiness probe reads the migration history (no RLS on Flyway's table; read-only).
GRANT SELECT ON app.flyway_schema_history TO app_runtime;

-- ---------------------------------------------------------------- 8. reference data (owner in maintenance mode)
SELECT set_config('app.maintenance_mode', 'on', true);
INSERT INTO app.feature_flags (key, description, default_enabled) VALUES
  ('photos_enabled',    'Private photo gallery with consent (EPIC 15)', false),
  ('messaging_enabled', 'Parent-teacher/admin messaging (EPIC 14)',     false),
  ('meals_enabled',     'Daily/weekly menus (EPIC 13)',                  false),
  ('calendar_enabled',  'Calendar events (EPIC 12)',                     false),
  ('payments_enabled',  'Parent payments (P2)',                          false);
