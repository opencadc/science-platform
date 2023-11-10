#!/bin/bash

HOST=$1
TOKEN=$2

mkdir -p ${HOME}
cd ${HOME}


mkdir -p ${HOME}/.token
echo $TOKEN > ${HOME}/.token/Bearer

echo "TOKEN is ${TOKEN}"
echo "HOST is ${HOST}"

echo "[skaha] Starting skaha desktop container"
/skaha-system/build-menu.sh ${HOST}
if [[ $? -eq 0 ]]; then
  /dockerstartup/vnc_startup.sh
  echo "[skaha] Exit"
else
  echo "[skaha] Error exit"
  exit 1
fi
