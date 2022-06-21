#!/bin/bash

CASA_VERSION=${1:-6.5}

docker push images.canfar.net/skaha/casa6-notebook:${CASA_VERSION}
