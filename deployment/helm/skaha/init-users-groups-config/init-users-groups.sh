#!/bin/bash

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

while read USER_ENTRY; do
        echo "${USER_ENTRY}" >> /etc-passwd/passwd
done < "${UID_MAP_FILE}"

while read GROUP_ENTRY; do
        echo "${GROUP_ENTRY}" >> /etc-group/group
done < "${GID_MAP_FILE}"

# restore $IFS
IFS=$SAVEIFS