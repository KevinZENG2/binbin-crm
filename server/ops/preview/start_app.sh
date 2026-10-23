#!/bin/sh
set -eu
umask 077
secret_dir=/run/crm-preview
CRM_DB_PASSWORD=$(cat "$secret_dir/runtime_password")
CRM_MIGRATION_DB_PASSWORD=$(cat "$secret_dir/migrator_password")
CRM_SEED_PASSWORD=$(cat "$secret_dir/login_password")
CRM_INTEGRATION_ENCRYPTION_KEY=$(cat "$secret_dir/integration_key")
CRM_S3_ACCESS_KEY=$(cat "$secret_dir/s3_access_key")
CRM_S3_SECRET_KEY=$(cat "$secret_dir/s3_secret_key")
export CRM_DB_PASSWORD CRM_MIGRATION_DB_PASSWORD CRM_SEED_PASSWORD CRM_INTEGRATION_ENCRYPTION_KEY CRM_S3_ACCESS_KEY CRM_S3_SECRET_KEY
cp "$JAVA_HOME/lib/security/cacerts" /tmp/crm-preview-cacerts
keytool -importcert -noprompt -alias crm-preview-root -file /opt/crm/preview-ca.crt -keystore /tmp/crm-preview-cacerts -storepass changeit >/dev/null 2>&1
exec java -Xmx512m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Djavax.net.ssl.trustStore=/tmp/crm-preview-cacerts -jar /opt/crm/crm.jar --spring.flyway.locations=filesystem:/opt/crm/migrations
