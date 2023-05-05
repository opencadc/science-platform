#!/bin/bash
helm --namespace cadc-harbor install cadc-harbor bitnami/harbor -f values-keel-prod.yaml --version 16.4.6 --set postgresql.image.tag="11.15.0"
