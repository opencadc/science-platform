#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha/pluto:latest images.canfar.net/skaha/pluto:$t
done
unset TAGS
docker image list images.canfar.net/skaha/pluto
