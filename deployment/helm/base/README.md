# base

![Version: 0.3.3](https://img.shields.io/badge/Version-0.3.3-informational?style=flat-square) ![Type: application](https://img.shields.io/badge/Type-application-informational?style=flat-square) ![AppVersion: 0.1.4](https://img.shields.io/badge/AppVersion-0.1.4-informational?style=flat-square)

## Description

A Helm chart to install base components of the CANFAR Science Platform

**Homepage:** <https://github.com/opencadc/science-platform>

## Maintainers

| Name | Email | Url |
| ---- | ------ | --- |
| Candian Astronomy Data Centre |  | <https://www.cadc-ccda.hia-iha.nrc-cnrc.gc.ca/> |

## Source Code

* <https://github.com/opencadc/science-platform/tree/main/deployment/helm/base>

## Requirements

Kubernetes: `>=1.26`

| Repository | Name | Version |
|------------|------|---------|
| https://traefik.github.io/charts | traefik(traefik) | 24.0.0 |

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| developer.storage.path | string | `nil` | **Dev Only:** The path to local storage for skaha, this path needs to exist on the host. |
| kubernetesClusterDomain | string | `"cluster.local"` | DNS domain name used within the Kubernetes cluster to allow service communication, e.g. service.namespace.svc.cluster.local |
| secrets.default-certificate | object | `{"tls.crt":null,"tls.key":null}` | **Dev Only:** The secret for Traefik Ingress SSL Termination, dont use in production!!! |
| secrets.default-certificate."tls.crt" | string | `nil` | **Dev Only:** Base64 encoded server certificate |
| secrets.default-certificate."tls.key" | string | `nil` | **Dev Only:** Base64 encoded server key |
| skaha.namespace | string | `"skaha-system"` | The namespace for the Skaha system components |
| skahaWorkload.namespace | string | `"skaha-workload"` | The namespace for the Skaha Workload components, e.g. User JupyterHub |
| traefik.install | bool | `false` | **Dev Only:** Whether to install Traefik (default: false) |
| traefik.logs.access.enabled | bool | `false` | **Dev Only:** To enable access logs |
| traefik.logs.general.format | string | `"common"` | **Dev Only:** The format of the logs, e.g. common, json, or logfmt |
| traefik.logs.general.level | string | `"ERROR"` | **Dev Only:** Logging level: DEBUG, PANIC, FATAL, ERROR, WARN, and INFO. |
| traefik.tlsOptions | object | `{}` | **Dev Only:** See dev.overrides.yaml for more options |
| traefik.tlsStore | object | `{}` | **Dev Only:** See dev.overrides.yaml for more options |

## Installation

### From source

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```bash
git clone https://github.com/opencadc/science-platform.git
cd science-platform/deployment/helm/
helm install --dependency-update --values ./base/values.yaml base ./base
```

### From the CANFAR repository

The Helm repository contains the current stable version as well.

```bash
> helm repo add canfar-skaha-system https://images.canfar.net/chartrepo/skaha-system
> helm repo update
> helm install --dependency-update --values canfar-skaha-system/base/values.yaml base canfar-skaha-system/base
```

## Developer Notes

To install the base chart with the developer overrides, you can use the `dev.overrides.yaml` file.

```bash
helm install --dependency-update --values ./base/values.yaml --values ./dev.overrides.yaml base ./base
```

### Proxy using Traefik

The [Traefik](https://traefik.io/traefik/) proxy server is also installed as a dependency, which handles SSL termination. 
Helm options are under the `traefik` key in the `values.yaml` file.

You can create your own secrets to contain your self-signed server certificates to be used by the SSL termination.
See the `dev.overrides.yaml` file for more, and don't forget to `base64` encode the values.

### Shared Storage

Shared Storage is handled by the `hostPath` Kubernetes provisioner, which means that the storage is local to the node. This
is useful for development and testing, but not recommended for production. To provision a shared storage volume, you have to
modify the `dev.overrides.yaml` file to include the volume configuration.

```yaml
developer:
  storage:
    path: /path/that/exists/on/host
```

Alternatively, you can also find sample manifests in the `volumes` directory, to create a `PersistentVolume` and `PersistentVolumeClaim`
for the shared storage manually, via a `kubectl apply -f` command.

### DNS on macOS

While runnung Kubernetes on a MacOS, through Docker Desktop, the Docker VM
cannot mount the NFS by default as it cannot do name resolution in the k8s cluster.
It first needs to know about the `kube-dns` IP.  e.g.:

```sh
$ kubectl -n kube-system get service kube-dns
NAME       TYPE        CLUSTER-IP   EXTERNAL-IP   PORT(S)                  AGE
kube-dns   ClusterIP   10.96.0.10   <none>        53/UDP,53/TCP,9153/TCP   1d11h
```

The `ClusterIP` needs to be known to the Docker VM's name resolution.
A simple way to do this is to mount the Docker VM root and modify it directly:

```sh
$ docker run --rm -it -v /:/vm-root alpine sh
$ echo "nameserver 10.96.0.10" >> /vm-root/etc/resolv.conf
$ cat /vm-root/etc/resolv.conf
# DNS requests are forwarded to the host. DHCP DNS options are ignored.
nameserver 192.168.65.7
nameserver 10.96.0.10
```

----------------------------------------------
Autogenerated from chart metadata using [helm-docs v1.14.2](https://github.com/norwoodj/helm-docs/releases/v1.14.2)
