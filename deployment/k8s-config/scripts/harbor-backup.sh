#!/bin/bash


#https://github.com/restic/restic/releases/download/v0.12.1/restic_0.12.1_linux_amd64.bz2
#https://restic.readthedocs.io/en/stable/index.html
#https://github.com/stonezdj/harbor/blob/backup_restore/tools/harbor-backup.sh


RESTIC_REPO='s3:harbor-repo'

KEY_ID=''
ACCESS_KEY=''
PASSWORD=''

POSTGRES_POD='cadc-harbor-postgresql-0'
REDIS_POD='cadc-harbor-redis-master-0'
REGISTRY_POD='cadc-harbor-registry'

POSTGRES_DIR='/bitnami/postgresql'
REDIS_DIR='/data'
REGISTRY_DIR='/storage/docker/registry'

PGPASSWORD=''
PGDUMP_DIR='/tmp/harbor'

#Backup Postgresql repo files
kubectl cp restic_0121 cadc-harbor/$POSTGRES_POD:/tmp/.
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose backup $POSTGRES_DIR"

#Backup Postgresql database dump (just to be extra safe)
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export PGPASSWORD=$PGPASSWORD ; mkdir -p $PGDUMP_DIR ; pg_dump -U postgres registry > $PGDUMP_DIR/registry.back ; pg_dump -U postgres postgres > $PGDUMP_DIR/postgres.back ; pg_dump -U postgres notarysigner > $PGDUMP_DIR/notarysigner.back ; pg_dump -U postgres notaryserver > $PGDUMP_DIR/notaryserver.back"
kubectl exec -it --namespace=cadc-harbor $POSTGRES_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose backup $PGDUMP_DIR"

#Backup Redis
kubectl cp restic_0121 cadc-harbor/$REDIS_POD:/tmp/.
kubectl exec -it --namespace=cadc-harbor $REDIS_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose backup $REDIS_DIR"

#Backup Registry (this one contains container images and is big)
kubectl cp restic_0121 cadc-harbor/$REGISTRY_POD:/tmp/.
kubectl exec -it --namespace=cadc-harbor $REGISTRY_POD -- bash -c "export AWS_ACCESS_KEY_ID=$KEY_ID ; export AWS_SECRET_ACCESS_KEY=$ACCESS_KEY ; export RESTIC_PASSWORD=$PASSWORD ; ./tmp/restic_0121 -r $RESTIC_REPO --verbose backup $REGISTRY_DIR"




