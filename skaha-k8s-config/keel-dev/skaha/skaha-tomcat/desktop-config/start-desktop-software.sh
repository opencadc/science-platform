#!/bin/bash

USERID=$1
TITLE=$2

echo "[skaha] Starting skaha software container [title=$TITLE] for [userid=$USERID]"

INITFILE=/skaha/init.sh
if [ -f "$INITFILE" ]; then
    echo "[skaha] Calling /skaha/init.sh"
    /skaha/init.sh
fi

echo "[skaha] Starting xterm"
xterm -fg white -bg black -title $TITLE 

echo "[skaha] Exit"
