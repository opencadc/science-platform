#!/bin/bash

kubectl create configmap arcade-config --from-file=config
kubectl create configmap start-desktop-software --from-file=desktop-config
