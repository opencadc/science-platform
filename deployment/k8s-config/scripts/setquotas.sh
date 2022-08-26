#!/bin/bash
DEFAULTQUOTA=200000000000
PATH=/cephfs/cavern/home
re='^[0-9]+$'

for directory in $PATH/*; do
    echo $directory
    SIZE=$(/usr/bin/du -sb $directory | /usr/bin/sed 's/[^0-9]*//g')
    QUOTA=$(/usr/bin/getfattr --only-values $directory)
    QUOTA=${QUOTA//[!0-9]/}
    ((DIFF = QUOTA - SIZE))
    echo "quota: $QUOTA  size: $SIZE diff: $DIFF"

    if ! [[ $QUOTA =~ $re ]] || [[ -z $QUOTA ]]
    then
      echo "quota not set"

      (( NEWQUOTA = SIZE*3/2 ))

      if [[ $NEWQUOTA -lt $DEFAULTQUOTA ]]
      then
        (( NEWQUOTA = DEFAULTQUOTA ))
      fi

      echo $NEWQUOTA
      /usr/bin/setfattr -n ceph.quota.max_bytes -v $NEWQUOTA $directory
      /usr/bin/setfattr -n user.ivo://ivoa.net/vospace/core#quota -v $NEWQUOTA $directory
    else
      echo "quota set"
    fi
done
