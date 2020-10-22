#!/bin/bash

kubectl -n arcade-system create configmap cavern-config --from-file=config
