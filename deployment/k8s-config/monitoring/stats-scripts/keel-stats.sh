#!/bin/bash

while [ : ]
do

  tot_c=0
  tot_m=0
  nodes=($(kubectl -n skaha-workload get nodes -o custom-columns=:metadata.name))
  for n in ${nodes[@]}
  do
    if [[ "$n" != *"master"* ]]; then
      node_cores=($(kubectl -n skaha-workload describe node $n | grep cpu: | sed 's/[^0-9]*//g'))
      node_mem=($(kubectl -n skaha-workload describe node $n | grep memory: | sed 's/[^0-9]*//g'))

      n_c=${node_cores[0]}
      n_m=$((${node_mem[1]}/1000000))

      tot_c=$((tot_c+n_c))
      tot_m=$((tot_m+n_m))
    fi
  done


  #-------------------------------------------------------------
  open_c=0
  cores=($(kubectl -n cadc-openharbor get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    open_c=$((open_c+i))
  done
  open_c=$((open_c/1000))

  open_m=0
  mem=($(kubectl -n cadc-openharbor get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    open_m=$(((open_m+i)/1024))
  done

  #-------------------------------------------------------------
  harbor_c=0
  cores=($(kubectl -n cadc-harbor get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    harbor_c=$((harbor_c+i))
  done
  harbor_c=$((harbor_c/1000))

  harbor_m=0
  mem=($(kubectl -n cadc-harbor get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    harbor_m=$(((harbor_m+i)/1024))
  done


  #-------------------------------------------------------------
  sssd_c=0
  cores=($(kubectl -n cadc-sssd get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0 
    else
      i=$((i*1000))
    fi
    sssd_c=$((sssd_c+i))
  done
  sssd_c=$((sssd_c/1000))

  sssd_m=0
  mem=($(kubectl -n cadc-sssd get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    sssd_m=$((sssd_m+i))
  done


  #-------------------------------------------------------------
  loki_c=0
  cores=($(kubectl -n cadc-loki get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    loki_c=$((loki_c+i))
  done
  loki_c=$((loki_c/1000))

  loki_m=0
  mem=($(kubectl -n cadc-loki get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    loki_m=$((loki_m+i))
  done


  #-------------------------------------------------------------
  system_c=0
  cores=($(kubectl -n skaha-system get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    system_c=$((system_c+i))
  done
  system_c=$((system_c/1000))

  system_m=0
  mem=($(kubectl -n skaha-system get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    system_m=$((system_m+i))
  done


  #-------------------------------------------------------------
  work_c=0
  cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector status.phase!=Pending))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    work_c=$((work_c+i))
  done
  work_c=$((work_c/1000))

  work_m=0
  mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector status.phase!=Pending | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
    work_m=$((work_m+i))
  done



  #-------------------------------------------------------------
  run_c=0
  cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Running))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    run_c=$((run_c+i))
  done
  run_c=$((run_c/1000))

  run_m=0
  mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Running | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
          run_m=$((run_m+i))
  done



  #-------------------------------------------------------------
  comp_c=0
  cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Succeeded))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    comp_c=$((comp_c+i))
  done
  comp_c=$((comp_c/1000))

  comp_m=0
  mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Succeeded | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
          comp_m=$((comp_m+i))
  done



  #-------------------------------------------------------------
  fail_c=0
  cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Failed))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    fail_c=$((fail_c+i))
  done
  fail_c=$((fail_c/1000))

  fail_m=0
  mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Failed | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
          fail_m=$((fail_m+i))
  done


  #-------------------------------------------------------------
  unkn_c=0
  cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Unknown))
  for i in ${cores[@]}
  do
    if [[ "$i" == *"m" ]]; then
      i=$(tr -dc '0-9' <<< $i)
    elif [[ "$i" == *"none"* ]]; then
      i=0
    else
      i=$((i*1000))
    fi
    unkn_c=$((unkn_c+i))
  done
  unkn_c=$((unkn_c/1000))

  unkn_m=0
  mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Unknown | sed 's/[^0-9]*//g'))
  for i in ${mem[@]}
  do
          unkn_m=$((unkn_m+i))
  done

  #-------------------------------------------------------------



  #keel_c=$((work_c+system_c+open_c+harbor_c+sssd_c+loki_c))
  #keel_m=$((work_m+system_m+open_m+harbor_m+sssd_m+loki_m))

  keel_c=$((work_c+system_c))
  keel_m=$((work_m+system_m))

  jd=$(date +%s)


  #echo "$jd , $tot_c , $tot_m , $keel_c , $keel_m , $run_c , $run_m , $work_c , $work_m , $system_c , $system_m , $open_c , $open_m , $harbor_c , $harbor_m , $sssd_c , $sssd_m , $loki_c , $loki_m"

  echo "$jd , $tot_c , $tot_m , $keel_c , $keel_m , $run_c , $run_m , $comp_c , $comp_m , $fail_c , $fail_m , $unkn_c , $unkn_m , $work_c , $work_m , $system_c , $system_m" >> keel-stats.csv

  #echo "$jd , $tot_c , $tot_m , $keel_c , $keel_m , $run_c , $run_m , $comp_c , $comp_m , $fail_c , $fail_m , $unkn_c , $unkn_m , $work_c , $work_m , $system_c , $system_m"
  sleep 1h
done
