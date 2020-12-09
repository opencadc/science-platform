#!/bin/bash

USERID=$1
TITLE=$2

echo "[ARCADE] Starting arcade software container [title=$TITLE] for [userid=$USERID]"

INITFILE=/arcade/init.sh
if [ -f "$INITFILE" ]; then
    echo "[ARCADE] Calling /arcade/init.sh"
    /arcade/init.sh
fi

echo "[ARCADE] Starting xterm"
xterm -fg white -bg black -title $TITLE 

echo "[ARCADE] Exit"
