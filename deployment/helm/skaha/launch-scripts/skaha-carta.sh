#!/bin/bash

set -e

SELF=skaha-carta

TS=$(date)
echo "$TS $SELF START"

if [ "$#" -ne 2 ]; then
    echo "Usage: skaha-carta <root> <folder>"
    exit 2
fi

ROOT=$1
FOLDER=$2
echo "root: $ROOT"
echo "folder: $FOLDER"
echo "command: carta --no_browser --top_level_folder=$ROOT --port=6901 --idle_timeout=100000 --debug_no_auth $FOLDER"
carta --no_browser --top_level_folder=$ROOT --port=6901 --idle_timeout=100000 --debug_no_auth $FOLDER
# A bit over a day timeout. Disable token authentication.
