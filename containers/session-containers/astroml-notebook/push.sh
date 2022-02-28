#!/bin/bash

TAG=$(date +%y.%m)

for i in jax torch flow; do
    for c in cpu gpu; do
	docker push images.canfar.net/skaha/astro${i}-notebook:${c}.${TAG}
    done
done


