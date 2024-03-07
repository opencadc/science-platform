#!/bin/sh

# Two arguments expected: 
# 1. the URL to the user (UID) mapping service
# 2. the URL to the group (GID) mapping service

cp /etc/passwd /etc-passwd/passwd
cp /etc/group /etc-group/group

UID_MAP_FILE="/posix-mapping/uidmap.txt"
GID_MAP_FILE="/posix-mapping/gidmap.txt"
SAVEIFS=$IFS
IFS='\n'

if [[ ! -f "${UID_MAP_FILE}" ]]; then
    echo "Required file ${UID_MAP_FILE} is missing."
    exit 1
fi

if [[ ! -f "${GID_MAP_FILE}" ]]; then
    echo "Required file ${GID_MAP_FILE} is missing."
    exit 1
fi

cat "${UID_MAP_FILE}" >> /etc-passwd/passwd
cat "${GID_MAP_FILE}" >> /etc-group/group

# restore $IFS
IFS=$SAVEIFS
