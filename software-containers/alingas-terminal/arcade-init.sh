#!/bin/bash

echo "in arcade-init.sh"
echo "HOST_NAME: $1"
echo "USER_NAME: $2"
echo "POSIX_ID $3"
echo "SESSION_ID: $4"
echo "SESSION_NAME: $5"
echo "SESSION_TYPE: $6"
echo "ARCADE_JOB_NAME: $7"
echo "SOFTWARE_JOB_NAME: $8"
echo "CONTAINER_NAME: $9"
echo "CONTAINER_PARAM: ${10}"
echo "TARGET_ID: ${11}"
echo "IMAGE_ID: ${12}"

function execute {
    cmd=$1
    arg=$2
    echo "$1 $2"
    msg="$($cmd $arg 2>&1)"
    rc=$?
    if [ $rc -eq 0 ]
    then
        echo "Sucessfully executed $cmd $arg"
    else
        if [ ${#msg} -gt 0 ]
        then
            echo "Failed to execute $cmd, return code = $rc, $msg"
        else
            echo "Failed to execute $cmd , return code = $rc, no stdout or stderr"
        fi
    fi
}

#echo "starting xterm $1"
#execute xterm "-fg white -bg black -title $1"
