#!/bin/bash

# This script removes orphaned service, ingress and middleware sessions with no corresponding pods.
# The script can be executed in its current directory by typing:
# ./cleanup.sh

# To DEBUG first:
# CLEANUP_DEBUG=1 ./cleanup.sh

echo "START $(date -u +"%Y%m%dT%H%M%S")"

if [[ "${CLEANUP_DEBUG}" == "1" ]]
then
  echo "DEBUG MODE"
fi

types="service ingressroute middleware"
for type in $types;
do
  kubectl -n skaha-workload get --no-headers=true $type |
    while IFS= read -r line
    # for each service session
    do
      # obtain various info on the session
      name=`echo $line | awk '{print $1}'`
      id=`echo $name | awk -F- '{print $NF}'`

      # check if there is a corresponding pod
      out=`kubectl -n skaha-workload get pods --ignore-not-found=true -l canfar-net-sessionID="${id}"`
      if [[ -z "$out" && -n "$id" ]]
      then
        CMD="kubectl -n skaha-workload delete $type $name"
        echo "${CMD}"

        # no corresponding pod, delete orphaned session
	      if [[ -z "${CLEANUP_DEBUG}" ]]
	      then
	        echo "Exec: ${CMD}"
	      fi
      elif [[ "${CLEANUP_DEBUG}" == "1" ]]
      then
	      echo "Nothing to do for Session ${id}"
      fi
    done
done

echo "END"