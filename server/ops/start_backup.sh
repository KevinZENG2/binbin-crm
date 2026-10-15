#!/bin/sh
set -eu
umask 077
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-boto3 python3-psycopg2 python3-cryptography
mkdir -p /opt/crm-ops
printf '%s' "$CRM_BACKUP_CODE" | base64 -d > /opt/crm-ops/backup.py
printf '%s  /opt/crm-ops/backup.py\n' "$CRM_BACKUP_CODE_SHA256" | sha256sum -c -
exec python3 /opt/crm-ops/backup.py
