#!/bin/bash

kubectl -n skaha-system create configmap arcade-wsproxy-config --from-file=config
