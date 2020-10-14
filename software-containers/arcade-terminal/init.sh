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
            echo "Failed to execute $cmd, return code = $rc, $msg"
        else
            echo "Failed to execute $cmd , return code = $rc, no stdout or stderr"
        fi
    fi
}

echo "do not auto activate base"
execute conda "config --set auto_activate_base false"
echo
echo "initialize conda environment"
execute conda "init bash"
echo
echo "use stroconda environment"
source $HOME/.bashrc && conda activate astroconda
