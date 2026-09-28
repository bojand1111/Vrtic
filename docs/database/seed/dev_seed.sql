-- =====================================================================================
--  DEVELOPMENT SEED — synthetic data only. Run as app_owner in maintenance mode:
--    psql -v ON_ERROR_STOP=1 -h localhost -p 5432 -U app_owner -d vrtic -f docs/database/seed/dev_seed.sql
--  Requires migrations V1..V5. Section 2 (children, guardians, schedules, absences, announcements,
--  calendar, menu) needs V5__core_business.
--
--  Scenario: Happy Kids (Novi Sad: Bubamare, Leptirići), owner + admin + 3 teachers + 12 parents,
--  and a second tenant "Sunčica" used ONLY for isolation tests.
--  No passwords in SQL. After loading, set a password for a seeded account with the DEV-only command:
--    SEED_DEV_PASSWORD='<your dev password>' java -jar vrtic-backend-all.jar dev-set-password vlasnik@happykids.example.test
--  (E02-D03: refuses outside APP_ENV=dev; the hash is Argon2id, the password never touches the repository).
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

-- ---------------------------------------------------------------- V5 business data (children, parents, daily operations)
-- Requires V5__core_business. Dates that describe "now" (absence, announcements, events, menu) are relative to
-- current_date, so a re-run on a later day adds the current week's menu without touching existing rows.
BEGIN;
SELECT set_config('app.maintenance_mode', 'on', true);

-- 15 children in Happy Kids: 8 Bubamare (1-3 years), 7 Leptirići (3-6 years); 1 child in Sunčica.
INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth, general_notes, created_by) VALUES
  ('88888888-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', 'Luka',    'Đorđević', '2024-03-12', 'Donosi svoju ćebence za spavanje.', '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 'Mia',     'Popović',  '2024-06-02', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000003', '22222222-0000-0000-0000-000000000001', 'Vuk',     'Lukić',    '2023-11-20', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000004', '22222222-0000-0000-0000-000000000001', 'Sara',    'Kostić',   '2024-01-15', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000005', '22222222-0000-0000-0000-000000000001', 'Ognjen',  'Simić',    '2024-09-05', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000006', '22222222-0000-0000-0000-000000000001', 'Tara',    'Živković', '2023-12-30', 'Voli da crta.', '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000007', '22222222-0000-0000-0000-000000000001', 'Pavle',   'Marković', '2024-04-18', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000008', '22222222-0000-0000-0000-000000000001', 'Nina',    'Ristić',   '2024-07-07', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000009', '22222222-0000-0000-0000-000000000001', 'Andrej',  'Đorđević', '2021-05-10', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000010', '22222222-0000-0000-0000-000000000001', 'Lena',    'Popović',  '2021-09-22', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000011', '22222222-0000-0000-0000-000000000001', 'Relja',   'Lukić',    '2022-02-14', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000012', '22222222-0000-0000-0000-000000000001', 'Iva',     'Simić',    '2021-12-01', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000013', '22222222-0000-0000-0000-000000000001', 'Dušan',   'Živković', '2022-06-19', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000014', '22222222-0000-0000-0000-000000000001', 'Ema',     'Kostić',   '2022-03-08', NULL, '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000015', '22222222-0000-0000-0000-000000000001', 'Filip',   'Jovanović','2022-10-03', 'Roditelji još nisu pozvani u aplikaciju.', '11111111-0000-0000-0000-000000000011'),
  ('88888888-0000-0000-0000-000000000020', '22222222-0000-0000-0000-000000000002', 'Jana',    'Tomić',    '2022-01-25', NULL, '11111111-0000-0000-0000-000000000090')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from, status, created_by)
SELECT c.organization_id, c.id,
       CASE WHEN c.organization_id = '22222222-0000-0000-0000-000000000002' THEN '55555555-0000-0000-0000-000000000009'::uuid
            WHEN c.date_of_birth >= DATE '2023-09-01' THEN '55555555-0000-0000-0000-000000000001'::uuid
            ELSE '55555555-0000-0000-0000-000000000002'::uuid END,
       DATE '2025-09-01', 'ACTIVE', '11111111-0000-0000-0000-000000000011'
FROM app.children c
WHERE c.id::text LIKE '88888888-%'
  AND NOT EXISTS (SELECT 1 FROM app.enrollments e WHERE e.child_id = c.id);

-- Guardians: couples share children; Lukić and Živković are single parents of two children;
-- Katarina Simić's link to Iva is PENDING (awaits staff confirmation); Filip has no guardian account yet.
INSERT INTO app.guardians (id, organization_id, child_id, membership_id, relationship, status, is_primary, confirmed_by, confirmed_at) VALUES
  ('99999999-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000030', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000031', 'MOTHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000003', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000009', '33333333-0000-0000-0000-000000000030', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000004', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000009', '33333333-0000-0000-0000-000000000031', 'MOTHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000005', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000002', '33333333-0000-0000-0000-000000000032', 'FATHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000006', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000002', '33333333-0000-0000-0000-000000000033', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000007', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000010', '33333333-0000-0000-0000-000000000032', 'FATHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000008', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000010', '33333333-0000-0000-0000-000000000033', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000009', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000003', '33333333-0000-0000-0000-000000000034', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000010', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000011', '33333333-0000-0000-0000-000000000034', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000011', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000004', '33333333-0000-0000-0000-000000000035', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000012', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000004', '33333333-0000-0000-0000-000000000036', 'FATHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000013', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000014', '33333333-0000-0000-0000-000000000035', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000014', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000014', '33333333-0000-0000-0000-000000000036', 'FATHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000015', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000007', '33333333-0000-0000-0000-000000000037', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000016', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000005', '33333333-0000-0000-0000-000000000038', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000017', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000005', '33333333-0000-0000-0000-000000000039', 'MOTHER', 'CONFIRMED', false, '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000018', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000012', '33333333-0000-0000-0000-000000000038', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000020', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000006', '33333333-0000-0000-0000-000000000040', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000021', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000013', '33333333-0000-0000-0000-000000000040', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000022', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000008', '33333333-0000-0000-0000-000000000041', 'MOTHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000011', now()),
  ('99999999-0000-0000-0000-000000000030', '22222222-0000-0000-0000-000000000002', '88888888-0000-0000-0000-000000000020', '33333333-0000-0000-0000-000000000092', 'FATHER', 'CONFIRMED', true,  '11111111-0000-0000-0000-000000000090', now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO app.guardians (id, organization_id, child_id, membership_id, relationship, status) VALUES
  ('99999999-0000-0000-0000-000000000019', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000012', '33333333-0000-0000-0000-000000000039', 'MOTHER', 'PENDING')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.pickup_persons (id, organization_id, child_id, full_name, relationship, phone, note, added_by_membership_id) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000001', 'Dragica Đorđević', 'Baka',  '+381 60 0000001', 'Utorkom i četvrtkom', '33333333-0000-0000-0000-000000000030'),
  ('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000003', 'Zoran Lukić',      'Deda',  '+381 60 0000002', NULL, '33333333-0000-0000-0000-000000000034'),
  ('aaaaaaaa-0000-0000-0000-000000000003', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000006', 'Jelena Živković',  'Tetka', '+381 60 0000003', NULL, '33333333-0000-0000-0000-000000000040')
ON CONFLICT (id) DO NOTHING;

-- Weekly templates from 2025-09-01: Mon-Fri 07:30-15:30 (Luka and Andrej 08:00-16:00); Mia does not come on Wednesdays.
INSERT INTO app.schedule_templates (id, organization_id, child_id, effective_from, created_by_membership_id)
SELECT ('bbbbbbbb-0000-0000-0000-0000000000' || right(c.id::text, 2))::uuid, c.organization_id, c.id, DATE '2025-09-01',
       CASE WHEN c.organization_id = '22222222-0000-0000-0000-000000000002' THEN '33333333-0000-0000-0000-000000000090'::uuid
            ELSE '33333333-0000-0000-0000-000000000011'::uuid END
FROM app.children c WHERE c.id::text LIKE '88888888-%'
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.schedule_template_days (organization_id, template_id, weekday, attends, arrival_time, departure_time)
SELECT t.organization_id, t.id, d.wd,
       (d.wd <= 5 AND NOT (t.child_id = '88888888-0000-0000-0000-000000000002' AND d.wd = 3)),
       CASE WHEN d.wd <= 5 AND NOT (t.child_id = '88888888-0000-0000-0000-000000000002' AND d.wd = 3)
            THEN CASE WHEN t.child_id IN ('88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000009') THEN TIME '08:00' ELSE TIME '07:30' END END,
       CASE WHEN d.wd <= 5 AND NOT (t.child_id = '88888888-0000-0000-0000-000000000002' AND d.wd = 3)
            THEN CASE WHEN t.child_id IN ('88888888-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000009') THEN TIME '16:00' ELSE TIME '15:30' END END
FROM app.schedule_templates t CROSS JOIN generate_series(1, 7) AS d(wd)
WHERE t.id::text LIKE 'bbbbbbbb-%'
ON CONFLICT (template_id, weekday) DO NOTHING;

-- Sara is sick today and tomorrow (reported by her mother).
INSERT INTO app.absences (id, organization_id, child_id, kind, date_from, date_to, note, reported_by_membership_id) VALUES
  ('cccccccc-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', '88888888-0000-0000-0000-000000000004', 'SICK', current_date, current_date + 1, 'Prehlada', '33333333-0000-0000-0000-000000000035')
ON CONFLICT (id) DO NOTHING;

-- Announcements: one organization-wide and one for Bubamare published (with recipient snapshots), one draft.
INSERT INTO app.announcements (id, organization_id, title, body, status, publish_at, published_at, created_by_membership_id) VALUES
  ('dddddddd-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', 'Dobro došli u Vrtić Connect',
   E'Dragi roditelji,\nod danas obaveštenja, odsustva i prisustvo pratimo kroz aplikaciju.\nZa pitanja smo tu svakog radnog dana.', 'PUBLISHED', now() - interval '2 days', now() - interval '2 days', '33333333-0000-0000-0000-000000000011'),
  ('dddddddd-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 'Bubamare: šetnja do parka u petak',
   E'U petak idemo u šetnju do obližnjeg parka.\nMolimo obucite decu u udobnu obuću i ponesite kapu.', 'PUBLISHED', now() - interval '1 day', now() - interval '1 day', '33333333-0000-0000-0000-000000000011'),
  ('dddddddd-0000-0000-0000-000000000003', '22222222-0000-0000-0000-000000000001', 'Roditeljski sastanak (nacrt)',
   'Nacrt obaveštenja o roditeljskom sastanku; datum još nije potvrđen.', 'DRAFT', NULL, NULL, '33333333-0000-0000-0000-000000000011')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app.announcement_audiences (organization_id, announcement_id, audience_type, group_id)
SELECT '22222222-0000-0000-0000-000000000001', a.id, a.t, a.g
FROM (VALUES ('dddddddd-0000-0000-0000-000000000001'::uuid, 'ORGANIZATION', NULL::uuid),
             ('dddddddd-0000-0000-0000-000000000002'::uuid, 'GROUP', '55555555-0000-0000-0000-000000000001'::uuid),
             ('dddddddd-0000-0000-0000-000000000003'::uuid, 'ORGANIZATION', NULL::uuid)) AS a(id, t, g)
WHERE NOT EXISTS (SELECT 1 FROM app.announcement_audiences x WHERE x.announcement_id = a.id);

INSERT INTO app.announcement_recipients (organization_id, announcement_id, membership_id)
SELECT m.organization_id, 'dddddddd-0000-0000-0000-000000000001', m.id
FROM app.organization_memberships m
WHERE m.organization_id = '22222222-0000-0000-0000-000000000001' AND m.status = 'ACTIVE'
ON CONFLICT (announcement_id, membership_id) DO NOTHING;

INSERT INTO app.announcement_recipients (organization_id, announcement_id, membership_id)
SELECT DISTINCT '22222222-0000-0000-0000-000000000001'::uuid, 'dddddddd-0000-0000-0000-000000000002'::uuid, r.membership_id
FROM (
  SELECT g.membership_id FROM app.guardians g JOIN app.enrollments e ON e.child_id = g.child_id
  WHERE g.status = 'CONFIRMED' AND e.group_id = '55555555-0000-0000-0000-000000000001' AND e.status = 'ACTIVE'
  UNION
  SELECT emp.membership_id FROM app.group_teacher_assignments a JOIN app.employees emp ON emp.id = a.employee_id
  WHERE a.group_id = '55555555-0000-0000-0000-000000000001' AND a.revoked_at IS NULL AND a.valid_to IS NULL
  UNION
  SELECT '33333333-0000-0000-0000-000000000011'::uuid
) r
ON CONFLICT (announcement_id, membership_id) DO NOTHING;

-- Calendar: parent meeting next week, Bubamare trip on Friday of this week, holiday next month.
INSERT INTO app.calendar_events (id, organization_id, kind, title, description, group_id, all_day, starts_on, ends_on, starts_at, ends_at, created_by_membership_id) VALUES
  ('eeeeeeee-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', 'PARENT_MEETING', 'Roditeljski sastanak', 'Upoznavanje sa planom rada za ovu godinu.', NULL, false,
   current_date + 7, current_date + 7, ((current_date + 7) + TIME '17:00') AT TIME ZONE 'Europe/Belgrade', ((current_date + 7) + TIME '18:00') AT TIME ZONE 'Europe/Belgrade', '33333333-0000-0000-0000-000000000011'),
  ('eeeeeeee-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001', 'TRIP', 'Šetnja do parka', 'Grupa Bubamare.', '55555555-0000-0000-0000-000000000001', true,
   date_trunc('week', current_date)::date + 4, date_trunc('week', current_date)::date + 4, NULL, NULL, '33333333-0000-0000-0000-000000000011'),
  ('eeeeeeee-0000-0000-0000-000000000003', '22222222-0000-0000-0000-000000000001', 'PHOTO_DAY', 'Dan fotografisanja', 'Grupno i pojedinačno fotografisanje.', NULL, true,
   current_date + 21, current_date + 21, NULL, NULL, '33333333-0000-0000-0000-000000000011')
ON CONFLICT (id) DO NOTHING;

-- Menu for the current week (Mon-Fri), published. Re-running on a later week adds that week.
INSERT INTO app.menu_days (organization_id, menu_date, is_published, created_by_membership_id)
SELECT '22222222-0000-0000-0000-000000000001', date_trunc('week', current_date)::date + d, true, '33333333-0000-0000-0000-000000000011'
FROM generate_series(0, 4) AS d
ON CONFLICT DO NOTHING;

INSERT INTO app.menu_items (organization_id, menu_day_id, meal_slot, description, allergen_tags, sort_order)
SELECT md.organization_id, md.id, x.slot,
       (ARRAY[x.d1, x.d2, x.d3, x.d4, x.d5])[extract(isodow FROM md.menu_date)::int], x.tags, 0
FROM app.menu_days md
CROSS JOIN (VALUES
  ('BREAKFAST', 'Kačamak sa jogurtom', 'Hleb, puter i med', 'Ovsene pahuljice sa voćem', 'Kajgana i integralni hleb', 'Palačinke sa džemom', ARRAY['MLEKO','GLUTEN']),
  ('SNACK_AM',  'Jabuka', 'Banana', 'Kruška', 'Mandarina', 'Grožđe', ARRAY[]::text[]),
  ('LUNCH',     'Pileća supa i rižoto sa povrćem', 'Varivo od boranije sa junetinom', 'Riba sa pire krompirom', 'Musaka od krompira', 'Pasulj i salata', ARRAY['CELER']),
  ('SNACK_PM',  'Jogurt i keks', 'Voćni kolač', 'Puding od vanile', 'Sendvič sa sirom', 'Kompot', ARRAY['MLEKO','GLUTEN','JAJA'])
) AS x(slot, d1, d2, d3, d4, d5, tags)
WHERE md.organization_id = '22222222-0000-0000-0000-000000000001' AND md.location_id IS NULL
  AND md.menu_date BETWEEN date_trunc('week', current_date)::date AND date_trunc('week', current_date)::date + 4
  AND NOT EXISTS (SELECT 1 FROM app.menu_items mi WHERE mi.menu_day_id = md.id);

COMMIT;
