#!/bin/bash

kubectl create -n skaha-system secret generic cavern-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
