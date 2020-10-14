#!/usr/bin/env bash

if [ $# -lt 1 ]
then
  echo "usage: $0 <version>"
  exit 1
fi

tag=`docker image ls | grep $1 | awk '{print $3}'`
if [ ${#tag} -gt 0 ]
then
    docker rmi -f $tag
fi
