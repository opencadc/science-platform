#!/bin/bash
helm upgrade -n cadc-harbor cadc-harbor bitnami/harbor -f values-keel-prod.yaml --version 7.4.3


