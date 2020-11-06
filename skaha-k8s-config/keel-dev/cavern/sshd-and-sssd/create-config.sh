#!/bin/bash

kubectl -n skaha-system create configmap cavern-sshd-config --from-file=config
