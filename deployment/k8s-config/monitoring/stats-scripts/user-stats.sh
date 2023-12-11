#!/bin/bash

#csv stats directory:
data="users"

mkdir "$data"

while [ : ]
do

	jd=$(date +%s)
	echo "Starting at $jd"

	#Find the name of an arc-tomcat pod:
	pod=$(kubectl -n skaha-system get pods --no-headers -o custom-columns=":metadata.name" | grep arc-tomcat | head -n 1)

	#Generate a list of all users:
	kubectl exec -it --namespace=skaha-system $pod -- bash -c 'ls /cephfs/cavern/home/ | tr " " "\n"' > all-users.txt
	
	#Generate a list of all active pod users, duplicated for each pod running:
	kubectl -n skaha-workload get pods --no-headers -o custom-columns=":metadata.name" | cut -d "-" -f 3 > active-users.txt

	#Loop through all-users.txt list:
	while read user; do

		#Remove any special characters from user name:
                u=$(echo "$user" | sed 's/[^[:alnum:]]//g')

		#Find the number of running pods for the user:
                n_p=$(grep -ic "$u" active-users.txt)

		#If the user has running pods:
                if [ $n_p -ne 0 ]; then
                        #echo $u, $n_p

			#Zero the counters:
			u_c=0
			u_m=0
			l_c=0
			l_m=0

			#Find number of requested cores:
			cores=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector=status.phase==Running -l canfar-net-userid=$u))
			for i in ${cores[@]}
			do
				if [[ "$i" == *"m" ]]; then
		 			i=$(tr -dc '0-9' <<< $i)
				elif [[ "$i" == *"none"* ]]; then
					i=0
				else
					i=$((i*1000))
				fi
				u_c=$((u_c+i))
			done
			u_c=$((u_c/1000))

			#Find amount of requested RAM:
 			mem=($(kubectl -n skaha-workload get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector=status.phase==Running -l canfar-net-userid=$u | sed 's/[^0-9]*//g'))
			for i in ${mem[@]}
			do
	      			u_m=$((u_m+i))
			done
	
			#Find actual cores load:
			cload=($(kubectl -n skaha-workload top pods --no-headers -l canfar-net-userid=$u | awk '{print $2}'))
        		for i in ${cload[@]}
        		do
                		if [[ "$i" == *"m" ]]; then
                        		i=$(tr -dc '0-9' <<< $i)
				elif [[ "$i" == *"none"* ]]; then
                        		i=0
                		else
                        		i=$((i*1000))
                		fi
                		l_c=$((l_c+i))
        		done
        		l_c=$((l_c/1000))

			#Find actual RAM load:
			mload=($(kubectl -n skaha-workload top pods --no-headers -l canfar-net-userid=$u | awk '{print $3}' | sed 's/[^0-9]*//g'))
        		for i in ${mload[@]}
        		do
              			l_m=$((l_m+i))
	        	done
			l_m=$((l_m/1000))

			#Get the UNIX time in seconds:
			jd=$(date +%s)

			#Output data to user file:
			echo "$jd , $u_c , $u_m , $l_c , $l_m , $n_p" >> $data/$u.csv
			#echo "$jd , $u_c , $u_m , $l_c , $l_m , $n_p"

		fi

	done < all-users.txt

	echo "Ending at $jd"

	sleep 1h
done
