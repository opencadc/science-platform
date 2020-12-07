#!/bin/bash

USERID=$1
TITLE=$2

echo "[ARCADE] Starting arcade software container [title=$TITLE] for [userid=$USERID]"
echo

INITFILE=/arcade/init.sh
if [ -f "$INITFILE" ]; then
    /bin/bash -c '/arcade/init.sh; xterm -fg white -bg black -title $TITLE '
else
    /bin/bash -c 'xterm -fg white -bg black -title $TITLE '
fi

echo "[ARCADE] End"
