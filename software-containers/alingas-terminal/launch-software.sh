#!/bin/bash

export HOST_NAME=$1
export USER_NAME=$2
export POSIX_ID=$3
export SESSION_ID=$4
export SESSION_NAME=$5
export SESSION_TYPE=$6
export ARCADE_JOB_NAME=$7
export SOFTWARE_JOB_NAME=$8
export CONTAINER_NAME=$9
export CONTAINER_PARAM=${10}
export TARGET_ID=${11}
export IMAGE_ID=${12}

FILE=/arcade/arcade-init.sh
if [ -f "$FILE" ]; then
    su -s /bin/bash -c 'echo "HOME: $HOME"; /arcade/arcade-init.sh $HOST_NAME $USER_NAME $POSIX_ID $SESSION_ID $SESSION_NAME $SESSION_TYPE $ARCADE_JOB_NAME $SOFTWARE_JOB_NAME $CONTAINER_NAME $CONTAINER_PARAM $TARGET_ID $IMAGE_ID; xterm -fg white -bg black -title $CONTAINER_NAME ' $USER_NAME
else
    su -s /bin/bash -c 'echo "No arcade-init.sh"; xterm -fg white -bg black -title $CONTAINER_NAME ' $USER_NAME
fi
