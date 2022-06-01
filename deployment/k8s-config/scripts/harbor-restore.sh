#!/bin/bash

RESTIC_REPO='s3:repo-url'
KEY_ID='<aws access key id>'
ACCESS_KEY='<aws access key>'
PASSWORD='<restic password>'

POSTGRES_POD='harbor-postgresql-0'
REDIS_POD='harbor-redis-master-0'
REGISTRY_POD='harbor-registry'

POSTGRES_DIR='/bitnami/postgresql'
REDIS_DIR='/data'
REGISTRY_DIR='/storage/docker/registry'

PGPASSWORD='<postgresql password>'
PGDUMP_DIR='/tmp/harbor'
RESTORE_DIR='/tmp/restore'

#*******************************************************
# Restore Postgresql DB tables
#*******************************************************

kubectl cp restic_0121 cadc-harbor/$POSTGRES_POD:/tmp/.

#List snapshots
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose snapshots"

#Restore DB dump files:
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO restore latest --target $RESTORE_DIR --path $PGDUMP_DIR --host $POSTGRES_POD"

#List restored files:
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "ls -lt $RESTORE_DIR$PGDUMP_DIR"

#Drop DB tables:
#kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "psql -U postgres -d template1 -c 'drop database registry;' ; psql -U postgres -d template1 -c 'drop database postgres;' ; psql -U postgres -d template1 -c 'drop database notarysigner;' ; psql -U postgres -d template1 -c 'drop database notaryserver;'"

#Create DB tables:
#kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "psql -U postgres -d template1 -c 'create database registry;' ; psql -U postgres -d template1 -c 'create database postgres;' ; psql -U postgres -d template1 -c 'create database notarysigner;' ; psql -U postgres -d template1 -c 'create database notaryserver;'"

#Restore DB tables from backup:
#kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export PGPASSWORD=$PGPASSWORD ; psql -U postgres registry < $PGRESTORE_DIR/registry.back ; psql -U postgres postgres < $RESTORE_DIR$PGDUMP_DIR/postgres.back ; psql -U postgres notarysigner < $RESTORE_DIR$PGDUMP_DIR/notarysigner.back ; psql -U postgres notaryserver < $RESTORE_DIR$PGDUMP_DIR/notaryserver.back"

#*******************************************************
# Restore Registry data
#*******************************************************

#Copy restic binary to pod:
kubectl cp restic_0121 cadc-harbor/$REGISTRY_POD:/tmp/.

#Restore Registry data from snapshot:
kubectl exec -it --namespace=cadc-harbor $REGISTRY_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose restore latest --target $RESTORE_DIR --path $REGISTRY_DIR"

#List restored files
kubectl exec -it --namespace=cadc-harbor $REGISTRY_POD -- bash -c "ls -lt $RESTORE_DIR"

#Copy restored data to active directory
#kubectl exec -it --namespace=cadc-harbor $REGISTRY_POD -- bash -c "cp -R $RESTORE_DIR$REGISTRY_DIR REGISTRY_DIR"


#*******************************************************
# Restore Redis data
#*******************************************************

#Copy restic binary to pod:
kubectl cp restic_0121 cadc-harbor/$REDIS_POD:/tmp/.

#Restore REDIS data from snapshot:
kubectl exec -it --namespace=cadc-harbor $REDIS_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose restore latest --target $RESTORE_DIR --path $REDIS_DIR --host $REDIS_POD" 

#List restored files
kubectl exec -it --namespace=cadc-harbor $REDIS_POD -- bash -c "ls -lt $RESTORE_DIR"

#Copy restored data to active directory
#kubectl exec -it --namespace=cadc-harbor $REDIS_POD -- bash -c "cp -R $RESTORE_DIR$REDIS_DIR REDIS_DIR"


