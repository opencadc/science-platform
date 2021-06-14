#!/bin/bash

USERID=$1
TITLE=$2

if [[ -z "${CASA_RELEASE}" ]];
  echo "not a casa container"
else
  echo "creating casa data repo link"
  ln -s /arc/projects/casa-data-repository/ /opt/${CASA_RELEASE}/data
fi

echo "[skaha] Starting skaha software container [title=$TITLE] for [userid=$USERID]"

INITFILE=/skaha/init.sh
if [ -f "$INITFILE" ]; then
    echo "[skaha] Calling /skaha/init.sh"
    /skaha/init.sh
fi

echo "[skaha] Starting xterm"
xterm -fg white -bg black -title $TITLE 

echo "[skaha] Exit"
