#!/bin/bash

kubectl -n skaha-system create configmap cavern-config --from-file=config
