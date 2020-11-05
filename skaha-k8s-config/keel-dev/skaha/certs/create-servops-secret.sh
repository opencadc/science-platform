#!/bin/bash

kubectl create -n skaha-system secret generic skaha-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
