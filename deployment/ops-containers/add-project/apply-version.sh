#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha-system/add-project:latest images.canfar.net/skaha-system/add-project:$t
done
unset TAGS
docker image list images.canfar.net/skaha-system/add-project
