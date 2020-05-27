#!/usr/bin/env bash

if [ $# -lt 2 ]
then
  echo "usage: $0 <version> <"el6"|"el7"|"latest">"
  exit 1
fi

RELEASE=$1
FILE="${RELEASE}.tar.gz"

if [ $2 == "el7" ]; then
  URL="https://casa.nrao.edu/download/distro/casa/release/el7/${FILE}"
elif [ $2 == "latest" ]; then
  URL="https://casa.nrao.edu/download/distro/casa-pipeline/release/el7/${FILE}"
else
  URL="https://casa.nrao.edu/download/distro/linux/release/el6/${FILE}"
fi

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
