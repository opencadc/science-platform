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
    echo "Disabling bell"
    xset -b 2>/dev/null
    echo "Disabling bell: OK"
    /skaha/startup.sh "xterm -fg white -bg black -title $TITLE -xrm 'XTerm*bellIsUrgent: false' -xrm 'XTerm*bellIsAudible: false'"
else
    echo "[skaha] Starting xterm"
    echo "Disabling bell"
    xset -b 2>/dev/null
    echo "Disabling bell: OK"
    xterm -fg white -bg black -title $TITLE -xrm 'XTerm*bellIsUrgent: false' -xrm 'XTerm*bellIsAudible: false'
fi

echo "[skaha] Exit"
