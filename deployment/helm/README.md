# Deployment Guide

## Dependencies

- An existing Kubernetes cluster.
- An IVOA Registry (See the [Current SKAO Registry](https://spsrc27.iaa.csic.es/reg))

## Quick Start

### Helm repository

Add the Helm repository:
```bash
helm repo add science-platform https://images.opencadc.org/chartrepo/platform
helm repo update
```

### Base install

The [Base](base) install will create ServiceAccount, Role, Namespace, and RBAC objects needed to place the Skaha service.

Create a `values-local.yaml` file to override Values from the main [template `values.yaml` file](base/values.yaml).  Mainly the
Traefik Default Server certificate (optional if needed):

`values-local.yaml`
```yaml
secrets:
    default-certificate:
        tls.crt: <base64 encoded server certificate>
        tls.key: <base64 encoded server key>
```

```bash
helm install --values values-local.yaml base science-platform/base

NAME: base
LAST DEPLOYED: Thu Sep 28 07:28:45 2023
NAMESPACE: default
STATUS: deployed
REVISION: 1
```

## Flow

![Skaha Flow](SkahaDependFlow.png)

The Skaha service depends on several installations being in place.

### Base
