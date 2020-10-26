#!/bin/bash

kubectl -n arcade-system create configmap arcade-config --from-file=config
kubectl -n arcade-system create configmap start-desktop-software --from-file=desktop-config
