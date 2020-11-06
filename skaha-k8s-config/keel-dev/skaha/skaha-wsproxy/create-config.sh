#!/bin/bash

kubectl -n skaha-system create configmap skaha-wsproxy-config --from-file=config
