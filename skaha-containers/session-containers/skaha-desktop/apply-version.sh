#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha/desktop:latest images.canfar.net/skaha/desktop:$t
done
unset TAGS
docker image list images.canfar.net/skaha/desktop
