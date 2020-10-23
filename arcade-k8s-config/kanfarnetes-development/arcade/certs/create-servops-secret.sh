#!/bin/bash

kubectl create secret generic arcade-servops-secret --from-file=cadcproxy.pem=./clientcert.pem
