#!/bin/bash
. VERSION && echo "tags: $TAGS"
for t in $TAGS; do
   docker image tag images.canfar.net/lsst/lsst_v21_0_0:latest images.canfar.net/lsst/lsst_v21_0_0:$t
done
unset TAGS
docker image list images.canfar.net/lsst/lsst_v21_0_0
