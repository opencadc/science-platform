#!/bin/bash

set -e

wrapper=""

mkdir -p ${HOME}
. /usr/local/bin/start.sh ${wrapper} jupyter lab --collaborative "$@"
