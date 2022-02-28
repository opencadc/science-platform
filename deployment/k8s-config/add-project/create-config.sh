#!/bin/bash

kubectl -n skaha-system create configmap add-project-config --from-file=config
