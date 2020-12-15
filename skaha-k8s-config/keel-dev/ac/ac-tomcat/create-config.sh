#!/bin/bash

kubectl create -n skaha-system configmap ac-config --from-file=ac-config
#kubectl create -n skaha-system configmap ac-tomcat-config --from-file=ac-tomcat-config
kubectl create -n skaha-system secret generic ac-dbrc --from-file=.dbrc=db-config/dbrc
