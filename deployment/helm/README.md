# Deployment Guide

## Dependencies

- An existing Kubernetes cluster.
- An IVOA Registry (See the [Current SKAO Registry](https://spsrc27.iaa.csic.es/reg))

## Quick Start

```bash
helm repo add science-platform https://images.opencadc.org/chartrepo/platform
helm repo update

helm install --values my-base-local-values-file.yaml base science-platform/base
helm install -n skaha-system --values my-posix-mapper-local-values-file.yaml posixmapper science-platform/posixmapper
helm install -n skaha-system --values my-skaha-local-values-file.yaml skaha science-platform/skaha
```

More details below.

### Helm repository

Add the Helm repository:
```bash
helm repo add science-platform https://images.opencadc.org/chartrepo/platform
helm repo update
```

### Base install

The [Base](base) install will create ServiceAccount, Role, Namespace, and RBAC objects needed to place the Skaha service.

Create a `base-values-local.yaml` file to override Values from the main [template `values.yaml` file](base/values.yaml).  Mainly the
Traefik Default Server certificate (optional if needed):

`base-values-local.yaml`
```yaml
secrets:
    default-certificate:
        tls.crt: <base64 encoded server certificate>
        tls.key: <base64 encoded server key>
```

```bash
helm install --values base-values-local.yaml base science-platform/base

NAME: base
LAST DEPLOYED: Thu Sep 28 07:28:45 2023
NAMESPACE: default
STATUS: deployed
REVISION: 1
```

### POSIX Mapper install

The [POSIX Mapper Service](posix-mapper) is required to provide a UID to Username mapping, and a GID to Group Name mapping so that any Terminal access properly showed System Users in User Sessions.  It will generate UIDs when a user is requested, or a GID when a Group is requested, and then keep track of them.

This service is required to be installed _before_ the Skaha service.

Create a `posix-mapper-values-local.yaml` file to override Values from the main [template `values.yaml` file](posix-mapper/values.yaml).

`posix-mapper-values-local.yaml`
```yaml
# POSIX Mapper web service deployment
deployment:
  hostname: example.host.com    # Change this!
  posixMapper:
    # Optionally set the DEBUG port.
    # extraEnv:
    # - name: CATALINA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    # - name: JAVA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"

    # Uncomment to debug.  Requires options above as well as service port exposure below.
    # extraPorts:
    # - containerPort: 5555
    #   protocol: TCP

    # Resources provided to the Skaha service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Used to set the minimum UID.  Useful to avoid conflicts.
    minUID: 1000

    # Used to set the minimum GID.  Keep this much higher than the minUID so that default Groups can be set for new users.
    minGID: 900000

    # The URL of the IVOA Registry
    registryURL: https://spsrc27.iaa.csic.es/reg

    # Optionally mount a custom CA certificate
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: posix-manager-cacert-secret

secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
#   posix-manager-cacert-secret:
#     ca.crt: <base64 encoded ca crt>

# These values are preset in the catalina.properties, and this default database only exists beside this service.
# It's usually safe to leave these as-is.
# postgresql:
#   auth:
#     username: posixmapper
#     password: posixmapperpwd
#     database: mapping
#     schema: mapping
#   storage:
#     spec:
#       hostPath:
#         path: "/posixmapper/data"

# An omission equals true, so set this explicitly to false as we already installed it.
base:
  install: false
```

It is recommended to install into the `skaha-system` namespace, but not required.
```bash
helm install -n skaha-system  --values posixmapper-values-local.yaml posixmapper science-platform/posixmapper

NAME: posixmapper
LAST DEPLOYED: Thu Sep 28 07:28:45 2023
NAMESPACE: skaha-system
STATUS: deployed
REVISION: 1
```

Test it.
```bash
# See below for tokens
export SKA_TOKEN=...
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" https://example.host.com/posix-mapper/uid

[]%

curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" "https://example.host.com/posix-mapper/uid?user=mynewuser"

mynewuser:x:1000:1000:::
```

### Skaha install

The Skaha service will manage User Sessions.  It relies on the POSIX Mapper being deployed, and available to be found
via the IVOA Registry:

`/reg/resource-caps`
```
...
# Ensure the hostname matches the deployment hostname.
ivo://opencadc.org/posix-mapper = https://example.host.com/posix-mapper/capabilities
...
```

```yaml
# POSIX Mapper web service deployment
deployment:
  hostname: example.host.com    # Change this!
  skaha:
    # Optionally set the DEBUG port.
    # extraEnv:
    # - name: CATALINA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"
    # - name: JAVA_OPTS
    #   value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5555"

    # Uncomment to debug.  Requires options above as well as service port exposure below.
    # extraPorts:
    # - containerPort: 5555
    #   protocol: TCP

    # Resources provided to the Skaha service.
    resources:
      requests:
        memory: "1Gi"
        cpu: "500m"
      limits:
        memory: "1Gi"
        cpu: "500m"

    # Directory to hold the user's home folders in the shared storage.
    homeDir: "/arc/home"

    maxUserSessions: "3"
    sessionExpiry: "345600"
    defaultQuotaGB: "10"

    # Space delimited list of allowed Image Registry hosts.  These hosts should match the hosts in the User Session images.
    registryHosts: "images.canfar.net"

    # The group name to verify users against for permission to use the Science Platform.
    usersGroup: "ivo://skao.int/gms?prototyping-groups/mini-src/platform-users"

    # The Resource ID of the Service that contains the Posix Mapping information
    posixMapperResourceID: "ivo://opencadc.org/posix-mapper"
    registryURL: https://nrc-023054.cadc.dao.nrc.ca/reg

    # Optionally mount a custom CA certificate
    # extraVolumeMounts:
    # - mountPath: "/config/cacerts"
    #   name: cacert-volume

    # Create the CA certificate volume to be mounted in extraVolumeMounts
    # extraVolumes:
    # - name: cacert-volume
    #   secret:
    #     defaultMode: 420
    #     secretName: posix-manager-cacert-secret

secrets:
  # Uncomment to enable local or self-signed CA certificates for your domain to be trusted.
#   posix-manager-cacert-secret:
#     ca.crt: <base64 encoded ca crt>

# An omission equals true, so set this explicitly to false as we already installed it.
posixmapper:
    install: false
    # Supports all of the posix-mapper/values.yaml options here.
```

It is recommended to install into the `skaha-system` namespace, but not required.
```bash
helm install -n skaha-system --values skaha-values-local.yaml skaha science-platform/skaha

NAME: skaha
LAST DEPLOYED: Thu Sep 28 07:31:10 2023
NAMESPACE: skaha-system
STATUS: deployed
REVISION: 1
```

Test it.
```bash
# See below for tokens
export SKA_TOKEN=...
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" https://example.host.com/skaha/v0/session

[]%


# xxxxxx is the returned session ID.
curl -SsL --header "authorization: Bearer ${SKA_TOKEN}" -d "ram=1" -d "cores=1" -d "image=images.canfar.net/canucs/canucs:1.2.5" -d "name=myjupyternotebook" "https://example.host.com/skaha/v0/session"

xxxxxx
```

## Obtaining a Bearer Token

See the [JIRA Confluence page](https://confluence.skatelescope.org/display/SRCSC/RED-10+Using+oidc-agent+to+authenticate+to+OpenCADC+services) on obtaining a Bearer Token.


## Flow

![Skaha Flow](SkahaDependFlow.png)

The Skaha service depends on several installations being in place.

## Structure

![Simple Skaha structure](./skaha.png)
