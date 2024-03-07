# Helm Chart for the Skaha web service CANFAR Science Platform

See the [Deployment Guide](../README.md) for a better idea of a full system.

## Install

The `values-local.yaml` and `values-k8s.yaml` provide different configuration for your deployment.  This `README` will focus on the
`values-local.yaml` as of 2023.08.22 to assume a local install on Docker Desktop.

It is assumed that the `base` install has already been performed.  See https://github.com/opencadc/science-platform/tree/SP-3544/deployment/helm/base.

It is assumed that the `posix-mapper` install has already been performed.  See https://github.com/opencadc/science-platform/tree/SP-3544/deployment/helm/posix-mapper.

It is also assumed that an IVOA Registry is running that will direct service lookups to appropriate URLs.

### From source

Installation depends on a working Kubernetes cluster version 1.23 or greater.

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml <name> ./skaha
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml skaha ./skaha
```
This will install Skaha service dependency, as well as the Skaha webservice and any necessary Ingress.
```
NAME: skaha
LAST DEPLOYED: <Timestamp e.g. Fri Jun 30 10:39:04 2023>
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

After the install, there should exist the necessary Namespaces and Objects.  See the Namespaces:

```sh
$ kubectl -n skaha-system get services
NAME                   STATUS   AGE
...
skaha-system   skaha-tomcat-svc             ClusterIP      10.108.202.148   <none>        8080/TCP            41m
```

The [IVOA VOSI availability](https://www.ivoa.net/documents/VOSI/20170524/REC-VOSI-1.1.html#tth_sEc5.5) endpoint can be used to 
check that the Skaha service has started properly.  It may take a few moments to start up.

```sh
$ curl https://myhost.example.com/skaha/availability

<?xml version="1.0" encoding="UTF-8"?>
<vosi:availability xmlns:vosi="http://www.ivoa.net/xml/VOSIAvailability/v1.0">
  <vosi:available>true</vosi:available>
  <vosi:note>skaha service is available.</vosi:note>
  <!--<clientip>192.1.1.4</clientip>-->
</vosi:availability>
```
