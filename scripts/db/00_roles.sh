#!/bin/bash
# Docker entrypoint hook (runs once on first cluster init, as the postgres superuser).
# Creates app_owner / app_runtime / app_worker roles and the application database.
set -euo pipefail
: "${APP_DB_NAME:?APP_DB_NAME is required}"
: "${APP_DB_OWNER_PASSWORD:?APP_DB_OWNER_PASSWORD is required}"
: "${APP_DB_RUNTIME_PASSWORD:?APP_DB_RUNTIME_PASSWORD is required}"
: "${APP_DB_WORKER_PASSWORD:?APP_DB_WORKER_PASSWORD is required}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v db_name="$APP_DB_NAME" \
  -v owner_pw="$APP_DB_OWNER_PASSWORD" \
  -v runtime_pw="$APP_DB_RUNTIME_PASSWORD" \
  -v worker_pw="$APP_DB_WORKER_PASSWORD" \
  -f /opt/vrtic/db/00_roles.sql
echo "[00_roles.sh] roles and database '$APP_DB_NAME' ready"
