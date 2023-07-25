#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/skaha/topcat-bypass-xterm:latest images.canfar.net/skaha/topcat-bypass-xterm:$t
done
unset TAGS
docker image list images.canfar.net/skaha/topcat-bypass-xterm
