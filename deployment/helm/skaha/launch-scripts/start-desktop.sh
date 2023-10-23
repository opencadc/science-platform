#!/bin/bash

HOST=$1

mkdir -p ${HOME}
cd ${HOME}


mkdir -p ${HOME}/.token
echo $2 > ${HOME}/.token/Bearer
TOKEN=$2
echo "TOKEN is ${TOKEN}"
echo "HOST is ${HOST}"

echo "[skaha] Starting skaha desktop container"
/skaha-system/build-menu.sh ${HOST} ${TOKEN}
if [[ $? -eq 0 ]]; then
  /dockerstartup/vnc_startup.sh
  echo "[skaha] Exit"
else
  echo "[skaha] Error exit"
  exit 1
fi
