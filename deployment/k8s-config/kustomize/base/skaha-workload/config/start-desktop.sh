#!/bin/bash

HOST=$1
echo "[skaha] Starting skaha desktop container"
/skaha-system/build-menu.sh ${HOST}
if [[ $? -eq 0 ]]; then
  /dockerstartup/vnc_startup.sh
  echo "[skaha] Exit"
else
  echo "[skaha] Error exit"
  exit 1
fi
