#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha-system/skaha:latest images.canfar.net/skaha-system/skaha:$t
done
unset TAGS
docker image list images.canfar.net/skaha-system/skaha
