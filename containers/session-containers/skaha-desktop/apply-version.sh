#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images-rc.canfar.net/skaha/desktop:latest images-rc.canfar.net/skaha/desktop:$t
done
unset TAGS
docker image list images-rc.canfar.net/skaha/desktop
