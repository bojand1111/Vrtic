-- =====================================================================================
--  V6: schedule exceptions (day overrides, closure days, schedule change log), in-app
--  notifications with device push tokens and delivery queue, parent-staff messaging.
--  Table definitions are copied verbatim from docs/database/schema.sql;
--  RLS, maintenance policies and privileges follow schema.sql sections 15-16.
-- =====================================================================================

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

-- Log of schedule changes (who changed what, was it late): separate from generic audit_log because
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

-- ---------------------------------------------------------------- RLS: tenant tables
DO $$
DECLARE
  t text;
  tenant_tables text[] := ARRAY[
    'schedule_day_overrides',
    'closure_days',
    'schedule_change_log',
    'conversations',
    'conversation_participants',
    'messages'
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

-- ---------------------------------------------------------------- RLS: notifications / device tokens: recipient only (schema.sql 15)
ALTER TABLE app.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY notifications_recipient ON app.notifications FOR ALL TO app_runtime
  USING (recipient_user_id = app.current_user_id())
  WITH CHECK (recipient_user_id = app.current_user_id() OR organization_id = app.current_organization_id());
ALTER TABLE app.device_push_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.device_push_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY device_tokens_owner ON app.device_push_tokens FOR ALL TO app_runtime
  USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id());
ALTER TABLE app.notification_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.notification_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY deliveries_worker ON app.notification_deliveries FOR ALL TO app_worker USING (true) WITH CHECK (true);
CREATE POLICY notifications_worker ON app.notifications FOR ALL TO app_worker USING (true) WITH CHECK (true);
CREATE POLICY device_tokens_worker ON app.device_push_tokens FOR ALL TO app_worker USING (true) WITH CHECK (true);

-- ---------------------------------------------------------------- owner maintenance on every new RLS table
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['schedule_day_overrides', 'closure_days', 'schedule_change_log', 'conversations', 'conversation_participants', 'messages', 'notifications', 'device_push_tokens', 'notification_deliveries'] LOOP
    EXECUTE format('CREATE POLICY owner_maintenance ON app.%I FOR ALL TO app_owner USING (app.maintenance_mode()) WITH CHECK (app.maintenance_mode())', t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- privileges
GRANT SELECT, INSERT, UPDATE ON app.schedule_day_overrides, app.closure_days, app.schedule_change_log, app.notifications,
  app.device_push_tokens, app.notification_deliveries, app.conversations, app.conversation_participants, app.messages TO app_runtime, app_worker;
GRANT DELETE ON app.device_push_tokens TO app_runtime;
GRANT DELETE ON app.closure_days TO app_runtime;
GRANT DELETE ON app.notification_deliveries TO app_worker;
REVOKE UPDATE ON app.schedule_change_log FROM app_runtime, app_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO app_runtime, app_worker;
