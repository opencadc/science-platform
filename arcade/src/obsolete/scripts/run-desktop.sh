#!/bin/bash
USER_ID=$1
SESSION_ID=$2
#VNC_PORT=$3
SESSION_NAME=$3
HOME_DIR=$4
SCRATCH_DIR=$5

docker run --rm -d --net=arcade-net --user 1001:1001 --label canfar-net-type=vnc --label canfar-net-userid=${USER_ID} --label canfar-net-sessionID=${SESSION_ID} --label canfar-net-sessionName=${SESSION_NAME} -e VNC_PW=${SESSION_ID} -e ARCADE_HTTP_PORT_443_TCP_ADDR=arcade.canfar.net --name session_${USER_ID}_${SESSION_ID}_${SESSION_NAME} -v ${HOME_DIR}/${USER_ID}:/home/guest -v ${SCRATCH_DIR}/${USER_ID}:/scratch -v /tmp/.X11-unix:/tmp/.X11-unix bucket.canfar.net/arcade-desktop:0.2.3
