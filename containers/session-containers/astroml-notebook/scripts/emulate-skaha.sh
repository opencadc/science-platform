#!/bin/bash

HOMEDIR=$(dirname ${HOME})
#       -v /etc/passwd:/etc/passwd \

# Import matplotlib the first time to build the font cache.
docker run --rm \
       -v ${HOME}:${HOME} \
       -e XDG_CACHE_HOME=/tmp/${USER}/.cache \
       -e XDG_RUNTIME_DIR="/tmp" \
       -e JUPYTER_CONFIG_DIR="${HOMEDIR}/${USER}" \
       -e JUPYTER_PATH="${HOMEDIR}/${USER}" \
       -e JUPYTER_ENABLE_LAB=yes \
       -e USER=${USER} \
       -e NB_USER="${USER}" \
       -e NB_UID="${UID}" \
       -e UID=${UID} \
       -e HOME="${HOME}" \
       -e PWD=${HOME} \
       -p 8888:8888 \
       $* \
       start-notebook.sh \       
       --ServerApp.notebook_dir=${HOME}  \
       --ServerApp.allow_origin="*"
#       --allow-root

