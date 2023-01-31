#!/bin/bash

namespace=$1
echo "Namespace: $namespace"
while [ : ]
do

  tot_c=0
  tot_m=0
  nodes=($(kubectl -n $namespace get nodes -o custom-columns=:metadata.name))
  for n in ${nodes[@]}
  do 
    node_cores=($(kubectl -n $namespace describe node $n | grep cpu: | sed 's/[^0-9]*//g'))
    node_mem=($(kubectl -n $namespace describe node $n | grep memory: | sed 's/[^0-9]*//g'))

    n_c=${node_cores[0]}
    n_m=$((${node_mem[1]}/1000000))

    tot_c=$((tot_c+n_c))
    tot_m=$((tot_m+n_m))
  done

  all_c=0
  cores=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    all_c=$((all_c+i))
  done


  all_m=0
  mem=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    all_m=$((all_m+i))
  done


  run_c=0
  cores=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Running))
  for i in ${cores[@]}
  do
    run_c=$((run_c+i))
  done


  run_m=0
  mem=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Running | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
          run_m=$((run_m+i))
  done


  jd=$(date +%s)

  echo "$jd , $all_c , $run_c , $tot_c , $all_m , $run_m , $tot_m"  >> log-stats.csv

  sleep 1h
done
