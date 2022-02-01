#!/bin/bash

HOST=$1
echo "[skaha] Starting skaha desktop container"
/skaha-system/build-menu.sh ${HOST}
/dockerstartup/vnc_startup.sh
echo "[skaha] Exit"
