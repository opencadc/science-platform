#!/bin/bash

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
            echo "Failed to executed $cmd, return code = $rc, $msg"
        else
            echo "Failed to execute $cmd , return code = $rc, no stdout or stderr"
        fi
    fi
}

echo "sourcing admit_start.sh"
execute source /opt/admit/admit_start.sh
echo
echo "starting xterm $1"
execute xterm "-title $1"
