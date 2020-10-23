#!/bin/bash

kubectl create -n cadc-sssd configmap sssd-config --from-file=config/sssd.conf
kubectl create -n cadc-sssd configmap sssd-cert-config --from-file=config/certs
