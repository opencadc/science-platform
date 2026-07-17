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

echo "Disabling bell"
xset -b 2>/dev/null
echo "Disabling bell: OK"

# SKAHA_XTERM_TITLE is used by start-desktop-software-xterm.sh to set the title of the xterm window.
export SKAHA_XTERM_TITLE="${TITLE}"

XTERM_LAUNCHER=/skaha-system/start-desktop-software-xterm.sh

if [ -f "$STARTUPFILE" ]; then
    echo "[skaha] Starting xterm via /skaha/startup.sh"
    # Pass a single launcher path so image startup.sh can "exec $@" without
    # re-splitting xterm options that contain spaces.
    /skaha/startup.sh "$XTERM_LAUNCHER"
else
    echo "[skaha] Starting xterm"
    exec "$XTERM_LAUNCHER"
fi
