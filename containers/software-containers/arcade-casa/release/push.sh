#!/usr/bin/env bash

if [ $# -lt 2 ]
then
  echo "usage: $0 <repo> <version>"
  exit 1
fi

dest="$1"
version="$2"

num=`docker image ls | grep $2 | wc -l`
if [ $num == 1 ]
then
    tag=`docker image ls | grep $2 | awk '{print $3}'`
    docker tag $tag $dest:$version
fi

num=`docker image ls | grep $2 | wc -l`
if [ $num == 2 ]
then
    docker push $1:$2 
fi
echo
