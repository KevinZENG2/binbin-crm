#!/bin/sh
# Disposable acceptance container only: a new local PostgreSQL database and Java API.
# Never attach a volume or use this command on an existing database host.
set -eu
test "${CRM_ISOLATED_ACCEPTANCE:-}" = "true"
test "${CRM_DB_URL:-}" = "jdbc:postgresql://127.0.0.1:5432/crm_acceptance?sslmode=require"
for crm_test_secret in "$CRM_ISOLATED_ROOT_PASSWORD" "$CRM_DB_PASSWORD" "$CRM_MIGRATION_DB_PASSWORD"; do
  test "${#crm_test_secret}" = "48"
  case "$crm_test_secret" in *[!a-f0-9]*) exit 1;; esac
done
umask 077
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates
mkdir -p /usr/share/postgresql-common/pgdg
chmod 0755 /usr/share/postgresql-common /usr/share/postgresql-common/pgdg
curl -fsS https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
chmod 0644 /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
crm_acceptance_codename="$(. /etc/os-release; printf '%s' "$VERSION_CODENAME")"
crm_acceptance_arch="$(dpkg --print-architecture)"
printf 'Types: deb\nURIs: https://apt.postgresql.org/pub/repos/apt\nSuites: %s-pgdg\nArchitectures: %s\nComponents: main\nSigned-By: /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc\n' "$crm_acceptance_codename" "$crm_acceptance_arch" > /etc/apt/sources.list.d/pgdg.sources
chmod 0644 /etc/apt/sources.list.d/pgdg.sources
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq postgresql-18 postgresql-client-18
crm_acceptance_pg_version="$(ls /etc/postgresql | sort -V | tail -1)"
printf "\nlisten_addresses = '*'\n" >> "/etc/postgresql/$crm_acceptance_pg_version/main/postgresql.conf"
printf "\nhost all all 0.0.0.0/0 scram-sha-256\nhost all all ::/0 scram-sha-256\n" >> "/etc/postgresql/$crm_acceptance_pg_version/main/pg_hba.conf"
pg_ctlcluster "$crm_acceptance_pg_version" main start
crm_acceptance_existing_databases="$(runuser -u postgres -- psql -X -Atc "SELECT count(*) FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'")"
crm_acceptance_existing_roles="$(runuser -u postgres -- psql -X -Atc "SELECT count(*) FROM pg_roles WHERE rolname <> 'postgres' AND rolname NOT LIKE 'pg_%'")"
test "$crm_acceptance_existing_databases" = "0"
test "$crm_acceptance_existing_roles" = "0"
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE crm_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '$CRM_DB_PASSWORD';
CREATE ROLE crm_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '$CRM_MIGRATION_DB_PASSWORD';
ALTER ROLE postgres PASSWORD '$CRM_ISOLATED_ROOT_PASSWORD';
CREATE DATABASE crm_acceptance;
SQL
runuser -u postgres -- psql -X -d crm_acceptance -v ON_ERROR_STOP=1 <<SQL
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE,CREATE ON SCHEMA public TO crm_migrator;
GRANT USAGE ON SCHEMA public TO crm_runtime;
SQL
mkdir -p /opt/crm
cd /opt/crm
test "${CRM_RELEASE_CHUNKS:-0}" -ge 1
test "$CRM_RELEASE_CHUNKS" -le 99
for crm_release_chunk in $(seq 1 "$CRM_RELEASE_CHUNKS"); do
  printenv "CRM_RELEASE_$(printf '%02d' "$crm_release_chunk")"
done | tr -d '\n' | base64 -d > release.tgz
printf '%s  release.tgz\n' "$CRM_RELEASE_SHA256" | sha256sum -c -
tar -xzf release.tgz
mvn -f server/pom.xml -B -ntp verify
exec java -XX:MaxRAMPercentage=50 -jar server/target/crm-server-0.16.0.jar --spring.web.resources.static-locations=file:/opt/crm/dist/
