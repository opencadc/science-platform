#!/bin/bash

# Three arguments expected: 
# 1. the current user's bearer token
# 2. the URL to the user (UID) mapping service
# 3. the URL to the group (GID) mapping service

cp /etc/passwd /etc-passwd/passwd
cp /etc/group /etc-group/group

SAVEIFS=$IFS
IFS=$(echo -en "\n\b")

USER_MAPPINGS=$(curl -SsL -k --header "accept: text/plain" --header "authorization: Bearer ${1}" "${2}")

# USER_MAPPINGS will be in the POSIX format already
for USER_ENTRY in "${USER_MAPPINGS}"; do
	echo "${USER_ENTRY}" >> /etc-passwd/passwd
done

GROUP_MAPPINGS=$(curl -SsL -k --header "accept: text/plain" --header "authorization: Bearer ${1}" "${3}")

# GROUP_MAPPINGS are a list of space delimited URIs to GIDs.  This loop
# will split at the URI's query ("?") to obtain the group name.
for GROUP_ENTRY in "${GROUP_MAPPINGS}"; do
        echo "${GROUP_ENTRY}" >> /etc-group/group
done

# restore $IFS
IFS=$SAVEIFS
