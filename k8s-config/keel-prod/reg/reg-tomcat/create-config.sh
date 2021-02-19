#!/bin/bash

kubectl -n skaha-system create configmap reg-config --from-file=reg-config
kubectl -n skaha-system create configmap reg-tomcat-config --from-file=reg-tomcat-config
