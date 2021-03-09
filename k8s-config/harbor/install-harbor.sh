#!/bin/bash
#helm install -v 9 -n cadc-harbor -f values.yaml cadc-harbor bitnami/harbor
helm install -n cadc-harbor cadc-harbor bitnami/harbor -f values.yaml --version 7.0.3


