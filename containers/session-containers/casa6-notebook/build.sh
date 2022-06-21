#!/bin/bash

CASA_VERSION=${1:-6.5}

docker build \
       -t images.canfar.net/skaha/casa6-notebook:${CASA_VERSION} \
       -f Dockerfile .
