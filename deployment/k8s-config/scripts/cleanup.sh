#!/bin/bash

# This script removes orphaned service, ingress and middleware sessions with no corresponding pods.
# The script can be executed in its current directory by typing:
# ./cleanup.sh

echo "START"

types="service ingressroute middleware"
for type in $types;
do
  kubectl -n skaha-workload get $type |
    while IFS= read -r line
    # for each service session
    do
      # obtain various info on the session
      name=`echo $line | awk '{print $1}'`
      id=`echo $name | awk -F- '{print $4}'`

      # check if there is a corresponding pod
      out=`kubectl -n skaha-workload get pods | grep -e "$id"`
      if [[ -z "$out" && -n "$id" ]]
      then
        # no corresponding pod, delete orphaned session
        kubectl -n skaha-workload delete $type $name
      fi
    done
done

echo "END"
