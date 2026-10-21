#!/bin/sh
# Only the first deployment needs a short-lived GitHub artifact download URL.
# Every subsequent start reads the verified private object, using existing S3 credentials.
set -eu
umask 077
: "${CRM_P7_RELEASE_SHA256:?Release checksum required}"
: "${CRM_P7_RELEASE_OBJECT_KEY:?Private release object required}"
: "${CRM_S3_ENDPOINT:?S3 endpoint required}"
: "${CRM_S3_BUCKET:?S3 bucket required}"
: "${CRM_S3_ACCESS_KEY:?S3 access key required}"
: "${CRM_S3_SECRET_KEY:?S3 secret key required}"
case "$CRM_S3_ENDPOINT" in https://*) ;; *) exit 1;; esac
case "$CRM_P7_RELEASE_OBJECT_KEY" in system/releases/crm/*) ;; *) exit 1;; esac
mkdir -p /opt/crm
cd /opt/crm
if ! command -v curl >/dev/null 2>&1; then
 apt-get update -qq
 DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates
fi
s3_host=${CRM_S3_ENDPOINT#https://}
s3_host=${s3_host%/}
s3_url="https://${CRM_S3_BUCKET}.${s3_host}/${CRM_P7_RELEASE_OBJECT_KEY}"
s3_request() {
 curl --retry 3 --connect-timeout 10 --max-time 90 -fsS --aws-sigv4 "aws:amz:${CRM_S3_REGION:-auto}:s3" --user "$CRM_S3_ACCESS_KEY:$CRM_S3_SECRET_KEY" "$@"
}
verify_release() { printf '%s  release.tgz\n' "$CRM_P7_RELEASE_SHA256" | sha256sum -c -; }
if s3_request "$s3_url" -o release.tgz; then
 verify_release
else
 : "${CRM_P7_BOOTSTRAP_URL:?Release object absent and no bootstrap artifact provided}"
 curl --retry 3 --connect-timeout 10 --max-time 90 -fsS "$CRM_P7_BOOTSTRAP_URL" -o bootstrap.zip
 jar xf bootstrap.zip crm-release.tgz
 mv crm-release.tgz release.tgz
 verify_release
 s3_request --upload-file release.tgz -H 'Content-Type: application/gzip' "$s3_url"
 s3_request "$s3_url" -o readback.tgz
 printf '%s  readback.tgz\n' "$CRM_P7_RELEASE_SHA256" | sha256sum -c -
 rm -f bootstrap.zip readback.tgz
 printf 'CRM_RELEASE_STAGED %s\n' "$CRM_P7_RELEASE_SHA256"
fi
tar -xzf release.tgz
touch dist/index.html
# SQL files are in the checksum-verified release; use an explicit path for Flyway.
mvn -f server/pom.xml -B -ntp verify
exec java -Xmx512m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -jar server/target/crm-server-0.22.0.jar --spring.web.resources.static-locations=file:/opt/crm/dist/ --spring.flyway.locations=filesystem:/opt/crm/server/src/main/resources/db/migration
