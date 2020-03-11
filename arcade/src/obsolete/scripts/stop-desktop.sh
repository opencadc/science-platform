#!/bin/bash
USER_ID=$1
SESSION_ID=$2
CONTAINER_ID=$(docker ps --format {{.ID}} --filter label=canfar-net-sessionID=$SESSION_ID)
docker kill $CONTAINER_ID
