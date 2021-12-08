#!/bin/bash

kubectl -n skaha-system create configmap add-user-config --from-file=config
