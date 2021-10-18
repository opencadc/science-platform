#!/bin/bash

kubectl -n skaha-workload get service |
  while IFS= read -r line
  do
    name=`echo $line | awk '{print $1}'`
    session=`echo $name | awk -F- '{print $2}'`
    id=`echo $name | awk -F- '{print $4}'`
    out=`kubectl -n skaha-workload get pods | grep $id`
    if [[ -z "$out" && -n "$id" ]]
    then
      kubectl -n skaha-workload delete service $name
      kubectl -n skaha-workload delete ingress skaha-${session}-ingress-$id
    fi
  done
