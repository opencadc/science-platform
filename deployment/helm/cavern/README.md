# Helm Chart for the Cavern User Storage API

See the [Deployment Guide](../README.md) for a better idea of a full system.

## Install

This `README` will focus on a basic install using a new `values-local.yaml` file.

A working Science Platform is not required, but the Persistent Volume Claims are needed.  Those PVs and PVCs will
provide the underlying storage for the Services and User Sessions.

### From source

Installation depends on a working Kubernetes cluster version 1.23 or greater.

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml <name> ./cavern
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml cavern ./cavern
```
This will install Skaha service dependency, as well as the Skaha webservice and any necessary Ingress.
```
NAME: cavern
LAST DEPLOYED: <Timestamp e.g. Fri Nov 07 04:19:04 2023>
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

After the install, there should exist the necessary Service.  See the Namespaces:

```sh
$ kubectl -n skaha-system get services
NAME                   STATUS   AGE
...
skaha-system   cavern-tomcat-svc             ClusterIP      10.108.202.148   <none>        8080/TCP            1m
```

The [IVOA VOSI availability](https://www.ivoa.net/documents/VOSI/20170524/REC-VOSI-1.1.html#tth_sEc5.5) endpoint can be used to 
check that the Skaha service has started properly.  It may take a few moments to start up.

```sh
$ curl https://myhost.example.com/cavern/availability

<?xml version="1.0" encoding="UTF-8"?>
<vosi:availability xmlns:vosi="http://www.ivoa.net/xml/VOSIAvailability/v1.0">
  <vosi:available>true</vosi:available>
  <vosi:note>service is accepting requests.</vosi:note>
  <!--<clientip>192.1.1.4</clientip>-->
</vosi:availability>
```
