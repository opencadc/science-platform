#!/bin/bash

# One argument expected: the skaha session ID, which makes up part of the connect url


cp /etc/passwd /etc-passwd/
cp /etc/group /etc-group/

DELIMITER=";"

USER_ENTRIES=($(awk -F"$DELIMITER" '{for(i=1; i<=NF; i++) print $i}' <<< "$1"))
for USER_ENTRY in "${USER_ENTRIES[@]}"; do
  	echo $USER_ENTRY >> /etc-passwd/passwd
done

GROUP_ENTRIES=($(awk -F"$DELIMITER" '{for(i=1; i<=NF; i++) print $i}' <<< "$2"))
for GROUP_ENTRY in "${GROUP_ENTRIES[@]}"; do
  	echo $GROUP_ENTRY >> /etc-group/group
done
