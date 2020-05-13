#!/usr/bin/env bash

if [ $# -eq 0 ]
then
  echo "usage: $0 <version>"
  exit 1
fi

RELEASE=$1
FILE="${RELEASE}.tar.gz"
URL="https://casa.nrao.edu/download/distro/casa/release/el7/${FILE}"

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
