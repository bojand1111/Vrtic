-- =====================================================================================
--  RLS NEGATIVE TESTS — run as app_runtime against a database with V1 applied and dev_seed loaded.
--    psql -v ON_ERROR_STOP=1 -h localhost -p 5432 -U app_runtime -d vrtic -f docs/database/tests/rls_negative_tests.sql
--  Every assertion raises an exception on failure => non-zero psql exit code = failed test.
--  Runs inside one transaction that is rolled back at the end (nothing left behind).
--  app_runtime has no TEMP/CREATE privileges by design, so all logic lives in DO blocks.
-- =====================================================================================
\set ON_ERROR_STOP on
BEGIN;

DO $test$
DECLARE
  passed int := 0;
  n bigint;
BEGIN
  -- ---------------------------------------------------------------- 1. no context
  PERFORM set_config('app.organization_id', '', true), set_config('app.user_id', '', true),
          set_config('app.auth_mode', '', true), set_config('app.platform_mode', '', true);

  SELECT count(*) INTO n FROM app.organizations;               IF n <> 0 THEN RAISE EXCEPTION 'no-context: organizations visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.locations;                   IF n <> 0 THEN RAISE EXCEPTION 'no-context: locations visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.organization_memberships;    IF n <> 0 THEN RAISE EXCEPTION 'no-context: memberships visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.users;                       IF n <> 0 THEN RAISE EXCEPTION 'no-context: users visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.sessions;                    IF n <> 0 THEN RAISE EXCEPTION 'no-context: sessions visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.children;                    IF n <> 0 THEN RAISE EXCEPTION 'no-context: children visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.guardians;                   IF n <> 0 THEN RAISE EXCEPTION 'no-context: guardians visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.absences;                    IF n <> 0 THEN RAISE EXCEPTION 'no-context: absences visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.attendance_days;             IF n <> 0 THEN RAISE EXCEPTION 'no-context: attendance_days visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.announcements;               IF n <> 0 THEN RAISE EXCEPTION 'no-context: announcements visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.messages;                    IF n <> 0 THEN RAISE EXCEPTION 'no-context: messages visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.conversations;               IF n <> 0 THEN RAISE EXCEPTION 'no-context: conversations visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.notifications;               IF n <> 0 THEN RAISE EXCEPTION 'no-context: notifications visible (%)', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.closure_days;                IF n <> 0 THEN RAISE EXCEPTION 'no-context: closure_days visible (%)', n; END IF; passed := passed + 1;

  BEGIN
    INSERT INTO app.locations (organization_id, name) VALUES ('22222222-0000-0000-0000-000000000001', 'x');
    RAISE EXCEPTION 'no-context: insert location was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;

  BEGIN
    INSERT INTO app.users (email, given_name, family_name) VALUES ('nobody@example.test', 'a', 'b');
    RAISE EXCEPTION 'no-context: insert user was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;

  -- ---------------------------------------------------------------- 2. tenant A (Happy Kids)
  PERFORM set_config('app.organization_id', '22222222-0000-0000-0000-000000000001', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000011', true);

  SELECT count(*) INTO n FROM app.organizations WHERE slug = 'happy-kids';  IF n <> 1 THEN RAISE EXCEPTION 'tenant A: own organization not visible'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.organizations WHERE slug = 'suncica';     IF n <> 0 THEN RAISE EXCEPTION 'tenant A: other organization visible'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.locations WHERE organization_id <> '22222222-0000-0000-0000-000000000001'; IF n <> 0 THEN RAISE EXCEPTION 'tenant A: foreign locations visible'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.groups;                                    IF n <> 2 THEN RAISE EXCEPTION 'tenant A: expected 2 own groups, got %', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.users WHERE email = 'vaspitac@suncica.example.test'; IF n <> 0 THEN RAISE EXCEPTION 'tenant A: other tenant user visible'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.users WHERE email = 'roditelj12@example.test';       IF n <> 1 THEN RAISE EXCEPTION 'tenant A: multi-tenant user not visible via A membership'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.organization_memberships WHERE organization_id = '22222222-0000-0000-0000-000000000002'; IF n <> 0 THEN RAISE EXCEPTION 'tenant A: B memberships visible'; END IF; passed := passed + 1;

  BEGIN
    INSERT INTO app.locations (organization_id, name) VALUES ('22222222-0000-0000-0000-000000000002', 'smuggled');
    RAISE EXCEPTION 'tenant A: insert into B was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;

  BEGIN
    INSERT INTO app.groups (organization_id, location_id, name) VALUES ('22222222-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000002', 'x');
    RAISE EXCEPTION 'tenant A: group pointing to B location was NOT denied';
  EXCEPTION WHEN insufficient_privilege OR foreign_key_violation THEN passed := passed + 1; END;

  -- UPDATE of another tenant's rows silently affects 0 rows (filtered by USING)
  UPDATE app.locations SET name = 'hacked' WHERE organization_id = '22222222-0000-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT;  IF n <> 0 THEN RAISE EXCEPTION 'tenant A: update touched % B rows', n; END IF; passed := passed + 1;

  -- the acting user may see only their own sessions (sessions_self); a demo login must not break this test
  SELECT count(*) INTO n FROM app.sessions WHERE user_id <> '11111111-0000-0000-0000-000000000011';
  IF n <> 0 THEN RAISE EXCEPTION 'tenant A: other users sessions visible'; END IF; passed := passed + 1;
  -- V5: children of tenant B (Jana Tomić) are invisible and cannot be linked from tenant A
  SELECT count(*) INTO n FROM app.children WHERE organization_id = '22222222-0000-0000-0000-000000000002';
  IF n <> 0 THEN RAISE EXCEPTION 'tenant A: tenant B children visible'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.children;  IF n = 0 THEN RAISE EXCEPTION 'tenant A: own children not visible'; END IF; passed := passed + 1;
  BEGIN
    INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship) VALUES
      ('22222222-0000-0000-0000-000000000002', '88888888-0000-0000-0000-000000000020', '33333333-0000-0000-0000-000000000092', 'OTHER');
    RAISE EXCEPTION 'tenant A: insert guardian into tenant B was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;
  SELECT count(*) INTO n FROM app.platform_admins; IF n <> 0 THEN RAISE EXCEPTION 'tenant A: platform_admins visible'; END IF; passed := passed + 1;

  -- ---------------------------------------------------------------- 3. tenant B (Sunčica)
  PERFORM set_config('app.organization_id', '22222222-0000-0000-0000-000000000002', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000090', true);
  SELECT count(*) INTO n FROM app.groups;                          IF n <> 1 THEN RAISE EXCEPTION 'tenant B: expected 1 group, got %', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.locations WHERE name = 'Novi Sad'; IF n <> 0 THEN RAISE EXCEPTION 'tenant B: Happy Kids location visible'; END IF; passed := passed + 1;

  -- ---------------------------------------------------------------- 4. user context without tenant (switcher)
  PERFORM set_config('app.organization_id', '', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000041', true);  -- PARENT in A, TEACHER in B
  SELECT count(*) INTO n FROM app.organization_memberships; IF n <> 2 THEN RAISE EXCEPTION 'switcher: expected 2 own memberships, got %', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.organizations;            IF n <> 2 THEN RAISE EXCEPTION 'switcher: expected 2 organizations, got %', n; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.groups;                   IF n <> 0 THEN RAISE EXCEPTION 'switcher: tenant rows visible without org context'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.users;                    IF n <> 1 THEN RAISE EXCEPTION 'switcher: expected only self in users, got %', n; END IF; passed := passed + 1;

  -- a user can READ own memberships but cannot modify them (no self-promotion / self-revocation): UPDATE hits 0 rows
  UPDATE app.organization_memberships SET role = 'OWNER' WHERE user_id = '11111111-0000-0000-0000-000000000041';
  GET DIAGNOSTICS n = ROW_COUNT;  IF n <> 0 THEN RAISE EXCEPTION 'switcher: user could modify own membership (% rows)', n; END IF; passed := passed + 1;

  -- revocation happens inside tenant B (by B staff); afterwards the user no longer sees organization B
  PERFORM set_config('app.organization_id', '22222222-0000-0000-0000-000000000002', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000090', true);
  UPDATE app.organization_memberships SET status = 'REVOKED', revoked_at = now()
   WHERE user_id = '11111111-0000-0000-0000-000000000041' AND organization_id = '22222222-0000-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT;  IF n <> 1 THEN RAISE EXCEPTION 'tenant B: revoke should update 1 row, got %', n; END IF; passed := passed + 1;
  PERFORM set_config('app.organization_id', '', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000041', true);
  SELECT count(*) INTO n FROM app.organizations;            IF n <> 1 THEN RAISE EXCEPTION 'switcher: revoked membership still shows organization'; END IF; passed := passed + 1;

  -- ---------------------------------------------------------------- 5. platform mode
  PERFORM set_config('app.user_id', '11111111-0000-0000-0000-000000000001', true), set_config('app.platform_mode', 'on', true);
  SELECT count(*) INTO n FROM app.organizations WHERE slug IN ('happy-kids','suncica'); IF n <> 2 THEN RAISE EXCEPTION 'platform: expected both seed organizations'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.locations;     IF n <> 0 THEN RAISE EXCEPTION 'platform: tenant tables visible without org context'; END IF; passed := passed + 1;
  PERFORM set_config('app.platform_mode', '', true);

  -- ---------------------------------------------------------------- 6. auth mode
  PERFORM set_config('app.auth_mode', 'on', true);
  SELECT count(*) INTO n FROM app.users WHERE email = 'vlasnik@happykids.example.test'; IF n <> 1 THEN RAISE EXCEPTION 'auth: user not readable'; END IF; passed := passed + 1;
  SELECT count(*) INTO n FROM app.groups; IF n <> 0 THEN RAISE EXCEPTION 'auth: tenant tables visible'; END IF; passed := passed + 1;
  PERFORM set_config('app.auth_mode', '', true);

  -- ---------------------------------------------------------------- 7. append-only audit log
  PERFORM set_config('app.organization_id', '22222222-0000-0000-0000-000000000001', true),
          set_config('app.user_id', '11111111-0000-0000-0000-000000000011', true);
  INSERT INTO app.audit_log (actor_user_id, organization_id, action, entity_type, result)
  VALUES ('11111111-0000-0000-0000-000000000011', '22222222-0000-0000-0000-000000000001', 'TEST', 'TEST', 'SUCCESS');
  BEGIN
    UPDATE app.audit_log SET action = 'x' WHERE action = 'TEST';
    RAISE EXCEPTION 'audit: update was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;
  BEGIN
    DELETE FROM app.audit_log WHERE action = 'TEST';
    RAISE EXCEPTION 'audit: delete was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;

  -- ---------------------------------------------------------------- 8. runtime cannot escalate
  BEGIN
    EXECUTE 'CREATE TABLE app.evil (id int)';
    RAISE EXCEPTION 'runtime: CREATE TABLE was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;
  BEGIN
    EXECUTE 'ALTER TABLE app.locations DISABLE ROW LEVEL SECURITY';
    RAISE EXCEPTION 'runtime: DISABLE RLS was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;
  BEGIN
    DELETE FROM app.organizations;
    RAISE EXCEPTION 'runtime: DELETE organizations was NOT denied';
  EXCEPTION WHEN insufficient_privilege THEN passed := passed + 1; END;

  RAISE NOTICE 'RLS negative tests: % assertions passed', passed;
END
$test$;

ROLLBACK;
