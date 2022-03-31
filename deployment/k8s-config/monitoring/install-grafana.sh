#!/bin/bash

helm install cadc-loki-grafana  -n cadc-loki grafana/grafana --values values-grafana.yaml
