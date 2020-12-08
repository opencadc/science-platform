#!/bin/bash

kubectl -n skaha-system create configmap skaha-config --from-file=config
kubectl -n skaha-workload create configmap start-desktop-software --from-file=desktop-config
