#!/bin/bash

kubectl create configmap sssd-config --from-file=config/sssd.conf
kubectl create configmap sssd-cert-config --from-file=config/certs
