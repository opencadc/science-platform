#!/bin/bash

# Three arguments expected: 
# 1. the current user's bearer token
# 2. the URL to the user (UID) mapping service
# 3. the URL to the group (GID) mapping service

cp /etc/passwd /etc-passwd/passwd
cp /etc/group /etc-group/group

SAVEIFS=$IFS
IFS=';'
# USER_MAPPINGS will be in the POSIX format already
echo "${2}" >> /etc-passwd/passwd

read -ra GROUP_MAPPINGS <<< "${3}"
# GROUP_MAPPINGS are a list of group entries concatenated with :
for GROUP_ENTRY in "${GROUP_MAPPINGS[@]}"; do
        echo "${GROUP_ENTRY}" >> /etc-group/group
done

# restore $IFS
IFS=$SAVEIFS
