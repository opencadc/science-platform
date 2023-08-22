# Helm Chart for the Skaha web service CANFAR Science Platform

## Install

The `values-local.yaml` and `values-k8s.yaml` provide different configuration for your deployment.  This `README` will focus on the
`values-local.yaml` as of 2023.08.22 to assume a local install on Docker Desktop.

It is assumed that the `base` install has already been performed.  See https://github.com/opencadc/science-platform/tree/SP-3544/deployment/helm/base.

It is also assumed that an IVOA Registry is running that will direct service lookups to appropriate URLs.  A sample deployment of such a Registry
can be found at https://github.com/at88mph/science-platform/tree/SP-3544-LOCAL/deployment/reg.  Copy it, update necessary values, and run `kubectl apply -k ./` inside the reg folder.

### Dependencies

A valid base64-encoded Client Proxy Certificate called `cadcproxy.pem` is required to be put into the list of secrets in `values-local.yaml` file.  

Kubernetes 1.14 and up are supported.

### From source

Installation depends on a working Kubernetes cluster version 1.23 or greater.

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install --dependency-update --values ./skaha/values-local.yaml <name> ./skaha
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install --dependency-update --values ./skaha/values-local.yaml canfar-science-platform-base ./skaha
```
This will install the Harbor service dependency, as well as the Skaha webservice and any necessary Ingress.
```
NAME: canfar-science-platform-skaha
LAST DEPLOYED: <Timestamp e.g. Fri Jun 30 10:39:04 2023>
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

After the install, there should exist the necessary Namespaces and Objects.  See the Namespaces:

```sh
$ kubectl get services -A
NAME                   STATUS   AGE
...
default        harbor                       ClusterIP      10.102.253.73    <none>        80/TCP,4443/TCP              41m
default        skaha-harbor-core            ClusterIP      10.100.19.190    <none>        80/TCP                       41m
default        skaha-harbor-database        ClusterIP      10.97.16.95      <none>        5432/TCP                     41m
default        skaha-harbor-jobservice      ClusterIP      10.99.11.91      <none>        80/TCP                       41m
default        skaha-harbor-notary-server   ClusterIP      10.106.9.205     <none>        4443/TCP                     41m
default        skaha-harbor-notary-signer   ClusterIP      10.102.43.0      <none>        7899/TCP                     41m
default        skaha-harbor-portal          ClusterIP      10.97.203.76     <none>        80/TCP                       41m
default        skaha-harbor-redis           ClusterIP      10.106.76.136    <none>        6379/TCP                     41m
default        skaha-harbor-registry        ClusterIP      10.97.41.121     <none>        5000/TCP,8080/TCP            41m
default        skaha-harbor-trivy           ClusterIP      10.100.142.130   <none>        8080/TCP                     41m
skaha-system   skaha-tomcat-svc             ClusterIP      10.108.202.148   <none>        8080/TCP,5555/TCP            41m
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
