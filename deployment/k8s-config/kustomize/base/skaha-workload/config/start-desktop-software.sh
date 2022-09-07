#!/bin/bash

USERID=$1
TITLE=$2

echo "[skaha] Starting skaha software container [title=$TITLE] for [userid=$USERID]"

INITFILE=/skaha/init.sh
STARTUPFILE=/skaha/startup.sh

if [ -f "$INITFILE" ]; then
    echo "[skaha] Calling /skaha/init.sh"
    /skaha/init.sh
fi

if [ -f "$STARTUPFILE" ]; then
    echo "[skaha] Starting xterm via /skaha/startup.sh"
    /skaha/startup.sh "xterm -fg white -bg black -title $TITLE"
else
    echo "[skaha] Starting xterm"
    xterm -fg white -bg black -title $TITLE
fi

echo "[skaha] Exit"
