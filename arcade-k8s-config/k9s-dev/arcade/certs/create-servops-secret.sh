#!/bin/bash

kubectl create -n skaha-system secret generic arcade-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
