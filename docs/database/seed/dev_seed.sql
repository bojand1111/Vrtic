-- =====================================================================================
--  DEVELOPMENT SEED — synthetic data only. Run as app_owner in maintenance mode:
--    psql -v ON_ERROR_STOP=1 -h localhost -p 5432 -U app_owner -d vrtic -f docs/database/seed/dev_seed.sql
--  Requires V1__foundation (EPIC 01). Children/guardian rows are added to this seed by the
--  EPIC 05 migration once those tables exist (see section "PENDING" at the bottom).
--
--  Scenario: Happy Kids (Novi Sad: Bubamare, Leptirići), owner + admin + 3 teachers + 12 parents,
--  and a second tenant "Sunčica" used ONLY for isolation tests.
--  No passwords: password_hash stays NULL until EPIC 02 hashes dev passwords from local config.
--  Never issues tokens or sessions.
-- =====================================================================================

\set ON_ERROR_STOP on

-- Refuse anything that is not a dev database. Mark a dev database once with:
--   ALTER DATABASE vrtic SET app.environment = 'dev';
DO $$
BEGIN
  IF COALESCE(current_setting('app.environment', true), '') <> 'dev' THEN
    RAISE EXCEPTION 'dev_seed refused: database is not marked as dev (ALTER DATABASE ... SET app.environment = ''dev'')';
  END IF;
END $$;

BEGIN;
SELECT set_config('app.maintenance_mode', 'on', true);

-- ---------------------------------------------------------------- users (synthetic, example.test)
INSERT INTO app.users (id, email, email_verified_at, given_name, family_name, preferred_locale) VALUES
  ('11111111-0000-0000-0000-000000000001', 'platform.admin@example.test', now(), 'Platform', 'Admin',     'en'),
  ('11111111-0000-0000-0000-000000000010', 'vlasnik@happykids.example.test',  now(), 'Milica',  'Petrović', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000011', 'admin@happykids.example.test',    now(), 'Jelena',  'Nikolić',  'sr-Latn'),
  ('11111111-0000-0000-0000-000000000020', 'vaspitac1@happykids.example.test', now(), 'Ana',    'Jovanović', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000021', 'vaspitac2@happykids.example.test', now(), 'Marko',  'Ilić',      'sr-Cyrl'),
  ('11111111-0000-0000-0000-000000000022', 'vaspitac3@happykids.example.test', now(), 'Ivana',  'Stanković', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000030', 'roditelj01@example.test', now(), 'Nikola',  'Đorđević', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000031', 'roditelj02@example.test', now(), 'Maja',    'Đorđević', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000032', 'roditelj03@example.test', now(), 'Stefan',  'Popović',  'sr-Latn'),
  ('11111111-0000-0000-0000-000000000033', 'roditelj04@example.test', now(), 'Tamara',  'Popović',  'sr-Cyrl'),
  ('11111111-0000-0000-0000-000000000034', 'roditelj05@example.test', now(), 'Dragan',  'Lukić',    'sr-Latn'),
  ('11111111-0000-0000-0000-000000000035', 'roditelj06@example.test', now(), 'Sanja',   'Kostić',   'sr-Latn'),
  ('11111111-0000-0000-0000-000000000036', 'roditelj07@example.test', now(), 'Vladimir','Kostić',   'sr-Latn'),
  ('11111111-0000-0000-0000-000000000037', 'roditelj08@example.test', now(), 'Jovana',  'Marković', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000038', 'roditelj09@example.test', now(), 'Miloš',   'Simić',    'en'),
  ('11111111-0000-0000-0000-000000000039', 'roditelj10@example.test', now(), 'Katarina','Simić',    'sr-Latn'),
  ('11111111-0000-0000-0000-000000000040', 'roditelj11@example.test', now(), 'Petar',   'Živković', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000041', 'roditelj12@example.test', now(), 'Milena',  'Ristić',   'sr-Latn'),
  -- second tenant
  ('11111111-0000-0000-0000-000000000090', 'vlasnik@suncica.example.test',   now(), 'Zoran',   'Pavlović', 'sr-Latn'),
  ('11111111-0000-0000-0000-000000000091', 'vaspitac@suncica.example.test',  now(), 'Nataša',  'Savić',    'sr-Latn'),
  ('11111111-0000-0000-0000-000000000092', 'roditelj@suncica.example.test',  now(), 'Igor',    'Tomić',    'sr-Latn')
ON CONFLICT (email) DO NOTHING;

INSERT INTO app.platform_admins (user_id, note) VALUES ('11111111-0000-0000-0000-000000000001', 'dev seed')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- organizations
INSERT INTO app.organizations (id, slug, name, legal_name, timezone, default_locale, created_by) VALUES
  ('22222222-0000-0000-0000-000000000001', 'happy-kids', 'Happy Kids', 'Happy Kids d.o.o. (synthetic)', 'Europe/Belgrade', 'sr-Latn', '11111111-0000-0000-0000-000000000001'),
  ('22222222-0000-0000-0000-000000000002', 'suncica',    'Sunčica',    'Sunčica (synthetic)',           'Europe/Belgrade', 'sr-Latn', '11111111-0000-0000-0000-000000000001')
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app.organization_settings (organization_id) VALUES
  ('22222222-0000-0000-0000-000000000001'), ('22222222-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- memberships (roles live here)
INSERT INTO app.organization_memberships (id, organization_id, user_id, role, status, accepted_at) VALUES
  ('33333333-0000-0000-0000-000000000010', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000010', 'OWNER',   'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000011', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000011', 'ADMIN',   'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000020', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000020', 'TEACHER', 'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000021', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000021', 'TEACHER', 'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000022', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000022', 'TEACHER', 'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000030', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000030', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000031', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000031', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000032', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000032', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000033', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000033', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000034', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000034', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000035', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000035', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000036', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000036', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000037', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000037', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000038', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000038', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000039', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000039', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000040', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000040', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000041', '22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000041', 'PARENT',  'ACTIVE', now()),
  -- multi-tenant user: roditelj12 is ALSO a teacher in Sunčica (role belongs to membership)
  ('33333333-0000-0000-0000-000000000090', '22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000090', 'OWNER',   'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000091', '22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000091', 'TEACHER', 'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000092', '22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000092', 'PARENT',  'ACTIVE', now()),
  ('33333333-0000-0000-0000-000000000093', '22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000041', 'TEACHER', 'ACTIVE', now())
ON CONFLICT (organization_id, user_id, role) DO NOTHING;

-- Admin gets health read explicitly (ownership/admin role alone does not).
INSERT INTO app.membership_permissions (organization_id, membership_id, permission, granted_by) VALUES
  ('22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000011', 'CHILD_HEALTH_READ', '11111111-0000-0000-0000-000000000010')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- locations, groups
INSERT INTO app.locations (id, organization_id, name, address_line, city, postal_code) VALUES
  ('44444444-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', 'Novi Sad', 'Bulevar oslobođenja 1 (synthetic)', 'Novi Sad', '21000'),
  ('44444444-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000002', 'Centar',   'Ulica 1 (synthetic)', 'Beograd', '11000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.groups (id, organization_id, location_id, name, age_from_months, age_to_months, capacity) VALUES
  ('55555555-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000001', 'Bubamare',  12, 36, 12),
  ('55555555-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000001', 'Leptirići', 36, 72, 18),
  ('55555555-0000-0000-0000-000000000009', '22222222-0000-0000-0000-000000000002', '44444444-0000-0000-0000-000000000002', 'Zvezdice',  12, 72, 15)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------- employees + assignments
INSERT INTO app.employees (id, organization_id, membership_id, display_name, job_title, primary_location_id, started_at) VALUES
  ('66666666-0000-0000-0000-000000000010', '22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000010', 'Milica Petrović', 'Direktor',  '44444444-0000-0000-0000-000000000001', '2024-09-01'),
  ('66666666-0000-0000-0000-000000000011', '22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000011', 'Jelena Nikolić',  'Administracija', '44444444-0000-0000-0000-000000000001', '2024-09-01'),
  ('66666666-0000-0000-0000-000000000020', '22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000020', 'Ana Jovanović',   'Vaspitač', '44444444-0000-0000-0000-000000000001', '2024-09-01'),
  ('66666666-0000-0000-0000-000000000021', '22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000021', 'Marko Ilić',      'Vaspitač', '44444444-0000-0000-0000-000000000001', '2025-01-15'),
  ('66666666-0000-0000-0000-000000000022', '22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000022', 'Ivana Stanković', 'Vaspitač', '44444444-0000-0000-0000-000000000001', '2025-09-01'),
  ('66666666-0000-0000-0000-000000000091', '22222222-0000-0000-0000-000000000002', '33333333-0000-0000-0000-000000000091', 'Nataša Savić',    'Vaspitač', '44444444-0000-0000-0000-000000000002', '2025-09-01')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.group_teacher_assignments (organization_id, group_id, employee_id, assignment_role, valid_from, valid_to) VALUES
  ('22222222-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000001', '66666666-0000-0000-0000-000000000020', 'LEAD',      '2025-09-01', NULL),
  ('22222222-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000002', '66666666-0000-0000-0000-000000000021', 'LEAD',      '2025-09-01', NULL),
  ('22222222-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000002', '66666666-0000-0000-0000-000000000022', 'ASSISTANT', '2025-09-01', NULL),
  -- expired assignment: Ivana was in Bubamare only last school year (teacher period tests)
  ('22222222-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000001', '66666666-0000-0000-0000-000000000022', 'ASSISTANT', '2024-09-01', '2025-06-30'),
  ('22222222-0000-0000-0000-000000000002', '55555555-0000-0000-0000-000000000009', '66666666-0000-0000-0000-000000000091', 'LEAD',      '2025-09-01', NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- subscriptions (plans catalogue is created by billing epic; a dev plan here)
INSERT INTO app.plans (id, code, version, name, currency, monthly_price_minor, entitlements, limits) VALUES
  ('77777777-0000-0000-0000-000000000001', 'STANDARD', 1, 'Standard (dev placeholder)', 'RSD', 0,
   '{"photos_enabled": true, "messaging_enabled": true, "meals_enabled": true, "calendar_enabled": true}',
   '{"max_children": 120, "max_locations": 3}')
ON CONFLICT (code, version) DO NOTHING;

INSERT INTO app.subscriptions (organization_id, plan_id, status, trial_ends_at, current_period_start, current_period_end) VALUES
  ('22222222-0000-0000-0000-000000000001', '77777777-0000-0000-0000-000000000001', 'TRIAL', now() + interval '30 days', now(), now() + interval '30 days'),
  ('22222222-0000-0000-0000-000000000002', '77777777-0000-0000-0000-000000000001', 'TRIAL', now() + interval '30 days', now(), now() + interval '30 days')
ON CONFLICT DO NOTHING;

COMMIT;

-- ---------------------------------------------------------------- PENDING (added when EPIC 05/07 migrations land)
-- * 15 children in Happy Kids (8 Bubamare, 7 Leptirići) with enrollments from 2025-09-01
-- * guardians: Đorđević, Popović, Kostić and Simić couples share children (two guardians per child);
--   Lukić and Živković have two children each (one parent, multiple children); one PENDING (unconfirmed) link
-- * pickup persons for three children; one child with has_critical_alert = true (encrypted payload)
-- * weekly templates (Mon–Fri 07:30–15:30 typical; Wednesday "ne dolazi" for one child), one absence
-- The seed must stay synthetic and refuse production (guard at the top).
