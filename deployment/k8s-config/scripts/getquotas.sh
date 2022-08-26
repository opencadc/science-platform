#!/bin/bash
PATH=/cephfs/cavern/home
re='^[0-9]+$'

for directory in $PATH/*; do
    echo $directory
    QUOTA=$(/usr/bin/getfattr --only-values $directory)
    QUOTA=${QUOTA//[!0-9]/}
    echo "quota: $QUOTA"
done
