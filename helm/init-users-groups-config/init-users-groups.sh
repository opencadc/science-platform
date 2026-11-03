#!/bin/sh

# It is expected that /etc-passwd and /etc-group exist and contain the original passwd files
# named passwd-orig and group-orig respectively.

# REDIS_URL expected to be in the environment.
# env:
#   - name: REDIS_URL
#     value: http://example.org:6359
#   - name: SKAHA_USERNAME   (optional) session owner; the container fails if they are missing from passwd.
#     value: username
#

# Fail the init container on any error, so a session never starts with incomplete passwd/group files.
set -eu

if [ -z "${REDIS_URL:-}" ]; then
    echo "Required argument REDIS_URL is missing."
    exit 1
else
    echo "Using REDIS_URL: ${REDIS_URL}"
fi

TARGET_PASSWD_FILE="/etc-passwd/passwd"
TARGET_GROUP_FILE="/etc-group/group"

# Create (or overwrite) the files
cat /etc-passwd/passwd-orig > "${TARGET_PASSWD_FILE}"
cat /etc-group/group-orig > "${TARGET_GROUP_FILE}"

# Append Science Platform users
redis-cli -u "${REDIS_URL}" --raw smembers "users:posix" >> "${TARGET_PASSWD_FILE}"
redis-cli -u "${REDIS_URL}" --raw smembers "groups:posix" >> "${TARGET_GROUP_FILE}"

if [ -n "${SKAHA_USERNAME:-}" ] && ! grep -q "^${SKAHA_USERNAME}:" "${TARGET_PASSWD_FILE}"; then
    echo "User ${SKAHA_USERNAME} has no passwd entry in ${REDIS_URL}."
    exit 1
fi
