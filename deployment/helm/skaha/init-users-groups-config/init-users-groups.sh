#!/bin/bash

# Depends on the images.opencadc.org/library/cadc-tomcat:1 image!

# Two variables are expected:
# REGISTRY_URL
#   The URL to the registry to use.
#   Example REGISTRY_URL=https://example.org/reg
#
# POSIX_MAPPER_URI
#   The IVO URI of the POSIX Mapper.  No absolute URLs.
#   Example POSIX_MAPPER_URI=ivo://example.org/posix-mapper
#

POSIX_MAPPER_PASSWD_FILE="${HOME}/.config/skaha/passwd"
POSIX_MAPPER_GROUP_FILE="${HOME}/.config/skaha/group"

PASSWD_FILE="/etc-passwd/passwd"
GROUP_FILE="/etc-group/group"

# Inject the HOME location for each POSIX entry.  This is necessary for Linux to set the current HOME for a user.
ESCAPED_HOME=`echo "${HOME}" | sed 's/\//\\\\\//g'`

# Backup existing files.
cp /etc/passwd "${PASSWD_FILE}"
cp /etc/group "${GROUP_FILE}"

cat "${POSIX_MAPPER_PASSWD_FILE}" | sed "s/^\([a-z]*\):\(.*\):::$/\1:\2::${ESCAPED_HOME}\/\1:\/sbin\/nologin/" >> "${PASSWD_FILE}"
cat "${POSIX_MAPPER_GROUP_FILE}" >> "${GROUP_FILE}"
