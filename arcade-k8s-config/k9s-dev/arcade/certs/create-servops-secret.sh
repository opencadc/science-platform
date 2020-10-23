#!/bin/bash

kubectl create -n arcade-system secret generic arcade-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
