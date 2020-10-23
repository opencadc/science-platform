#!/bin/bash

USERID=$1
TITLE=$2

echo "[ARCADE] Starting arcade software container [title=$TITLE] for [userid=$USERID]"
echo

INITFILE=/arcade/init.sh
if [ -f "$INITFILE" ]; then
    su -s /bin/bash -c '/arcade/init.sh; xterm -fg white -bg black -title $TITLE ' $USERID
else
    su -s /bin/bash -c 'xterm -fg white -bg black -title $TITLE ' $USERID
fi

echo "[ARCADE] End"
