#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag bucket.canfar.net/skaha-allocate:latest bucket.canfar.net/skaha-allocate:$t
done
unset TAGS
docker image list bucket.canfar.net/skaha-allocate
