#!/bin/bash

namespace=$1
echo "Namespace: $namespace"
nodes=($(kubectl -n $namespace get nodes -o custom-columns=:metadata.name))
u_c=0
u_m=0
t_c=0
t_m=0
for n in ${nodes[@]}
do 
	cores=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector spec.nodeName=$n))
        c=0
	for i in ${cores[@]}
	do
		if [[ "$i" == *"m" ]]; then
			i=$(tr -dc '0-9' <<< $i)
		else
			i=$((i*1000))
		fi
		c=$((c+i))
	done

        mem=($(kubectl -n $namespace get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector spec.nodeName=$n | sed 's/[^0-9]*//g'))

	m=0
        for i in ${mem[@]}
        do
                m=$((m+i))
        done
       

	node_cores=($(kubectl -n $namespace describe node $n | grep cpu: | sed 's/[^0-9]*//g'))
	
	node_mem=($(kubectl -n $namespace describe node $n | grep memory: | sed 's/[^0-9]*//g'))

	n_c=$((${node_cores[0]}*1000))
	n_m=$((${node_mem[1]}/1000000))
	f_c=$((($n_c-$c)/1000))
        f_m=$(($n_m-$m))

        u_c=$((u_c+c))
        u_m=$((u_m+m))
        t_c=$((t_c+n_c))
        t_m=$((t_m+n_m))

	#l_str=($(kubectl -n $namespace top node $n))
	#l_c=($(echo | awk -v var=$l_str '{print $3}' | sed 's/[^0-9]*//g'))
	#l_m=($(echo | awk -v var=$l_str '{print $5}' | sed 's/[^0-9]*//g'))
        l_c=($(kubectl -n $namespace top node $n | awk -F ' ' '{print $3}' | sed 's/[^0-9]*//g'))
        l_m=($(kubectl -n $namespace top node $n | awk -F ' ' '{print $5}' | sed 's/[^0-9]*//g'))

        echo "Node: $n  Cores: $c/$n_c  RAM: $m/$n_m GB  **Free Cores: $f_c RAM: $f_m  Load: CPU: $l_c% RAM: $l_m%"


done

u_c=$((u_c/1000))
t_c=$((t_c/1000))

echo "Total Cores: $u_c/$t_c"
echo "Total RAM: $u_m/$t_m GB"