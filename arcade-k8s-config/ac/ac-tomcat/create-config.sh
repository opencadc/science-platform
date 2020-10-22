#!/bin/bash

kubectl create configmap ac-config --from-file=ac-config
kubectl create configmap ac-tomcat-config --from-file=ac-tomcat-config
kubectl create secret generic ac-dbrc --from-file=.dbrc=db-config/dbrc
