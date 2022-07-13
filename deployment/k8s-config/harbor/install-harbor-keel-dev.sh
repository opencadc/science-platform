#!/bin/bash
helm install -n cadc-harbor cadc-harbor bitnami/harbor -f values-keel-dev.yaml --version 11.2.0

