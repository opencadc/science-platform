#!/bin/bash

kubectl -n arcade-system create configmap cavern-sshd-config --from-file=config
