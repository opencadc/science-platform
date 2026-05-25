#!/bin/bash

HOST=$1

mkdir -p ${HOME}
cd ${HOME}

mkdir -p ${HOME}/.token

echo "[skaha] Starting skaha desktop container"
/skaha-system/build-menu.sh ${HOST}
if [[ $? -eq 0 ]]; then
  /dockerstartup/vnc_startup.sh
  echo "[skaha] Exit"
else
  echo "[skaha] Error exit (${?}) from build-menu.sh"
  exit 1
fi
