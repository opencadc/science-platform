#!/bin/bash

kubectl create secret generic cavern-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
