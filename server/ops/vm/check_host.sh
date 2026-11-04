#!/bin/sh
# Read-only inventory. Do not print application environments or credentials.
set -eu
printf '\nIdentity\n'
id
printf '\nOperating system\n'
cat /etc/os-release
printf '\nMemory\n'
free -m
printf '\nDisk\n'
df -h / /opt
printf '\nListening TCP ports\n'
ss -lnt
printf '\nExisting Docker services\n'
if command -v docker >/dev/null 2>&1; then
    docker ps --format '{{.Names}} {{.Image}} {{.Status}} {{.Ports}}' || true
    docker compose version || true
fi
printf '\nExisting Nginx site names\n'
if command -v nginx >/dev/null 2>&1; then
    nginx -v 2>&1
    ls -l /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null || true
fi
printf '\nCRM domain IPv4 records\n'
getent ahostsv4 crm.jiayunsoft.com || true
printf '\nProject directories\n'
ls -ld /opt/storm-bus /opt/binbin-crm /opt/binbin-crm-preview 2>/dev/null || true
