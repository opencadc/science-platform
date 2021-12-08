#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha-system/add-user:latest images.canfar.net/skaha-system/add-user:$t
done
unset TAGS
docker image list images.canfar.net/skaha-system/add-user
