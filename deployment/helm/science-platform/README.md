# Helm Chart for the CANFAR Science Platform

## Install

This will install all necessary objects for running and listing user sessions on the Science Platform.

The [`values-template.yaml`](https://github.com/opencadc/science-platform/tree/SP-3544/deployment/helm/science-platform/values-template.yaml) can be used as a starting point for your configuration.  This `README` will focus on a local installation, such as the Kubernetes cluster that comes with Docker Desktop.

It is also assumed that an IVOA Registry is running that will direct service lookups to appropriate URLs.  A sample deployment of such a Registry
can be found at https://github.com/at88mph/science-platform/tree/SP-3544-LOCAL/deployment/reg.  Copy it, update necessary values, and run `kubectl apply -k ./` inside the reg folder.

### Configuration

Copy the `values-template.yaml` file into a `values-local.yaml` file.  This will be used to configure your specific needs.

A valid base64-encoded Client Proxy Certificate called `cadcproxy.pem` is required to be put into the list of secrets in `values-local.yaml` file.  

Kubernetes 1.23 and up are supported.

### From source

Installation depends on a working Kubernetes cluster version 1.23 or greater.

The Traefik proxy service can be installed by setting the `traefik.install` property, or omitting it if Traefik is already installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install --dependency-update --values ./science-platform/values-local.yaml <name> ./science-platform
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install --dependency-update --values ./science-platform/values-local.yaml science-platform ./science-platform
```
This will install the Skaha web service, as well as shared storage, and necessary Kubernetes objects
to launch user sessions.
```
NAME: science-platform
LAST DEPLOYED: <Timestamp e.g. Fri Jun 30 10:39:04 2023>
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

After the install, there should exist the necessary Namespaces and Objects.  See the Namespaces:

```sh
$ kubectl get services -n skaha-system
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
