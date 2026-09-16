-- =====================================================================================
--  Cluster-level roles for Vrtić Connect. Run ONCE per database cluster by a superuser.
--  The local Docker database runs it automatically through scripts/db/00_roles.sh.
--
--  Usage (psql variables carry the secrets; nothing is hard-coded here):
--    psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
--         -v db_name="$APP_DB_NAME" -v owner_pw="$APP_DB_OWNER_PASSWORD" \
--         -v runtime_pw="$APP_DB_RUNTIME_PASSWORD" -v worker_pw="$APP_DB_WORKER_PASSWORD" \
--         -f scripts/db/00_roles.sql
-- =====================================================================================

-- app_owner: owns schema `app` and every object; runs Flyway migrations. No superuser, no BYPASSRLS.
SELECT format('CREATE ROLE app_owner LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT', :'owner_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_owner') \gexec

-- app_runtime: used by the API process. Table privileges are granted by migrations; RLS is FORCED.
SELECT format('CREATE ROLE app_runtime LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT', :'runtime_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') \gexec

-- app_worker: background relay (outbox, notification deliveries, day-freeze). Separate credentials.
SELECT format('CREATE ROLE app_worker LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT', :'worker_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_worker') \gexec

-- Database owned by app_owner. Runtime roles only get CONNECT.
SELECT format('CREATE DATABASE %I OWNER app_owner', :'db_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO app_owner, app_runtime, app_worker', :'db_name') \gexec
SELECT format('ALTER ROLE app_owner   IN DATABASE %I SET search_path = app, public', :'db_name') \gexec
SELECT format('ALTER ROLE app_runtime IN DATABASE %I SET search_path = app, public', :'db_name') \gexec
SELECT format('ALTER ROLE app_worker  IN DATABASE %I SET search_path = app, public', :'db_name') \gexec

-- Inside the new database: lock down public and pre-create extensions so app_owner never needs superuser.
\connect :db_name
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;
