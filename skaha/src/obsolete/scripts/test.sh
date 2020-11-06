#!/bin/bash
CONTAINER_ID=$1
TARGET_IP=$2
USER_ID=$3
HOME_DIR=$4
SCRATCH_DIR=$5
PARAM=$6
docker run --rm -it --net=arcade-net -u 1001:1001 -e HOME=/home/guest -e DISPLAY=${TARGET_IP}:1 -v ${HOME_DIR}/${USER_ID}:/home/guest -v ${SCRATCH_DIR}/${USER_ID}:/scratch -v /tmp/.X11-unix:/tmp/.X11-unix $CONTAINER_ID $PARAM
#docker run --rm -d -it --net=databench-net -e HOME=/home/guest -e DISPLAY=${TARGET_IP}:1 -v ${HOME_DIR}/${USER_ID}:/home/guest -v ${SCRATCH_DIR}/${USER_ID}:/scratch -v /tmp/.X11-unix:/tmp/.X11-unix $CONTAINER_ID xterm
