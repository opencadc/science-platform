#!/bin/bash

# Two arguments expected: 
# 1. the URL to the user (UID) mapping service
# 2. the URL to the group (GID) mapping service

cp /etc/passwd /etc-passwd/passwd
cp /etc/group /etc-group/group

SAVEIFS=$IFS
IFS=';'

read -ra USER_MAPPINGS <<< "${1}"
# USER_MAPPINGS will be in the POSIX format already, delimited with ${IFS}
for USER_ENTRY in "${USER_MAPPINGS[@]}"; do
        echo "${USER_ENTRY}" >> /etc-passwd/passwd
done

read -ra GROUP_MAPPINGS <<< "${2}"
# GROUP_MAPPINGS are a list of group POSIX entries delimited with ${IFS}
for GROUP_ENTRY in "${GROUP_MAPPINGS[@]}"; do
        echo "${GROUP_ENTRY}" >> /etc-group/group
done

# restore $IFS
IFS=$SAVEIFS