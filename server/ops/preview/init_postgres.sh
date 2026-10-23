#!/bin/sh
# Invoked only by the official PostgreSQL image on a new empty cluster.
set -eu
CRM_RUNTIME_PASSWORD=$(cat /etc/crm-preview/runtime_password)
CRM_MIGRATOR_PASSWORD=$(cat /etc/crm-preview/migrator_password)
export CRM_RUNTIME_PASSWORD CRM_MIGRATOR_PASSWORD
psql -X --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv runtime_password CRM_RUNTIME_PASSWORD
\getenv migrator_password CRM_MIGRATOR_PASSWORD
CREATE ROLE crm_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'runtime_password';
CREATE ROLE crm_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'migrator_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE,CREATE ON SCHEMA public TO crm_migrator;
GRANT USAGE ON SCHEMA public TO crm_runtime;
SQL
unset CRM_RUNTIME_PASSWORD CRM_MIGRATOR_PASSWORD
