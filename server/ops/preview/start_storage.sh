#!/bin/sh
set -eu
MINIO_ROOT_USER=$(cat /etc/minio/s3_access_key)
MINIO_ROOT_PASSWORD=$(cat /etc/minio/s3_secret_key)
export MINIO_ROOT_USER MINIO_ROOT_PASSWORD
exec /usr/local/bin/minio "$@"
