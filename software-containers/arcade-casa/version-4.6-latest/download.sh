#!/usr/bin/env bash

if [ $# -lt 2 ]
then
  echo "usage: $0 <path> <version>"
  exit 1
fi

PATH=$1
VERSION=$2
FILE="${VERSION}.tar.gz"
URL="https://casa.nrao.edu/download/distro/${PATH}/${FILE}"

# make sure we are in the source folder
HERE=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
cd $HERE

if [ ! -e "$FILE" ]; then
    curl -O  $URL
else
    echo "$FILE already downloaded."
fi

FILE="admit"
URL="https://github.com/astroumd/${FILE}"
if [ ! -e "$FILE" ]; then
    git clone  $URL
else
    echo "$FILE already downloaded."
fi
