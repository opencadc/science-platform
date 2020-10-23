#!/bin/bash

kubectl -n arcade-system create configmap arcade-wsproxy-config --from-file=config
