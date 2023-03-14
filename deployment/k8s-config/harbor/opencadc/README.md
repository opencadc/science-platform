# OpenCADC Harbor Deployment Instructions

This document outlines the steps needed to deploy OpenCADC Harbor.

## Prerequisites:

Before proceeding with the deployment of Harbor, ensure the following is in place:

- Have access to a Kubernetes cluster

- Install helm on the node where Kubernetes cluster management is done, (eg. keel-prod-login).

- Git pull repo: https://github.com/opencadc/science-platform.git

- Create a Namespace for deployment. In this document we assume the cadc-openharbor namespace.

## Create Persistant Volumes:

Create Persistent Volumes as described in values-opencadc-prod.yaml and summarized here:

```
cadc-openharbor-chartmuseum-pv                40Gi       RWO
cadc-openharbor-jobservice-pv                 10Gi       RWO
cadc-openharbor-registry-pv                 1000Gi       RWO
data-cadc-openharbor-postgresql-0-pv           8Gi       RWO
data-cadc-openharbor-trivy-0-pv               40Gi       RWO
redis-data-cadc-openharbor-redis-master-0-pv   8Gi       RWO
```

If you don't have the required permissions (which is the case for Ops at the CADC) ask the administrators of the Kubernetes cluster to set these up.

## Deploy net certs:

First obtain certificates for the images and notary domain names, in this case images.opencadc.org and notary.opencadc.org. Put the certs in the certs in separate directories for images and notary.

Run the scripts create-tls-images.sh and create-tls-notary.sh, or alternately run the commands directly:
```
kubectl -n cadc-openharbor create secret tls images-net-cert-secret --key privkey.pem --cert fullchain.pem

kubectl -n cadc-openharbor create secret tls notary-net-cert-secret --key privkey.pem --cert fullchain.pem
```
 

If the secrets already exist and you want to update them, you can delete them and the issue the above commands: 
```
kubectl -n cadc-openharbor delete secret images-net-cert-secret

kubectl -n cadc-openharbor delete secret notary-net-cert-secret
```
## Deployment:

In the git repo `deployment/k8s-config/harbor/opencadc` execute:
```
helm install -n cadc-openharbor cadc-openharbor bitnami/harbor -f values-opencadc-prod.yaml
```

The harbor deployment should now start up.

## Configure Harbor:

Use the default admin password given in the values-opencadc-prod.yaml to login and then within the UI under the admin username on the top right, change the password. If this isn't the first deployment, the changed password will be stored within the persistent volume from the previous deployment.

In the Configuration tab on the left setup the OIDC authentification using the following settings:
```
Auth Mode: OIDC
OIDC Provider Name: .....
OIDC Endpoint: .....
OIDC Client ID: .....
OIDC Client Secret: .....
OIDC Group Filter: [empty]
Group Claim Name: memberOf
OIDC Admin Group: [empty]
OIDC Scope: openid
```

You can find the OIDC Client ID and Secret values within the file ac-oidc-clients.properties in the puppet-remote git repository.

## Uninstall a deployment:

To uninstall a deployment issue this command:
```
helm uninstall -n cadc-openharbor cadc-openharbor
```
