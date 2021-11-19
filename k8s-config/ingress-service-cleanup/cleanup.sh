#!/bin/bash

# This script removes orphaned service and ingress sessions with no corresponding pods.
# The script can be executed in its current directory by typing:
# ./cleanup.sh

kubectl -n skaha-workload get service |
  while IFS= read -r line
  # for each service session
  do
    # obtain various info on the session
    name=`echo $line | awk '{print $1}'`
    session=`echo $name | awk -F- '{print $2}'`
    id=`echo $name | awk -F- '{print $4}'`
    # check if there is a corresponding pod
    out=`kubectl -n skaha-workload get pods | grep $id`
    if [[ -z "$out" && -n "$id" ]]
    then
      # no corresponding pod, delete orphaned sessions
      kubectl -n skaha-workload delete service $name
      kubectl -n skaha-workload delete ingress skaha-${session}-ingress-$id
    fi
  done
