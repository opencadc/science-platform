#!/bin/bash

kubectl -n skaha-system create configmap arcade-config --from-file=config
kubectl -n skaha-system create configmap start-desktop-software --from-file=desktop-config
