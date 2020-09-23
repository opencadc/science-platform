#!/usr/bin/env bash

if [ $# -lt 2 ]
then
  echo "usage: $0 <repo> <version>"
  exit 1
fi

src=$1
version=$2
docker pull $1:$2 
