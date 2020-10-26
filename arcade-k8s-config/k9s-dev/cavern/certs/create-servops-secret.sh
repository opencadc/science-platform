#!/bin/bash

kubectl create -n arcade-system secret generic cavern-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
