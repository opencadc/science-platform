#!/bin/bash

TAG=$(date +%y.%m)

docker build -t canfar/astroml:latest -f Dockerfile.common .

for i in jax torch flow; do
    for c in cpu gpu; do
	docker build -t images.canfar.net/skaha/astro${i}-notebook:${c}.${TAG} -f Dockerfile.${i}.${c} .
    done
done
