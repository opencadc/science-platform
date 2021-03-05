#!/bin/bash

kubectl -n skaha-system create configmap allocate-config --from-file=config
