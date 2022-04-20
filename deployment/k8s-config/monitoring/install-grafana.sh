#!/bin/bash

helm upgrade --install cadc-loki-grafana  --namespace cadc-loki grafana/grafana --values values-grafana.yaml
