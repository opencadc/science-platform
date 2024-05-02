# Helm Chart for the Science Platform SSHD service

See the [Deployment Guide](../README.md) for a better idea of a full system.

## Install from Helm repository

Create a Helm values file.

`my-sshd-values.yaml`:
```yaml
deployment:
  sshd:
    registryURL: https://example.org/reg
    posixMapperResourceID: ivo://example.org/posix-mapper
    entryPoint: sshd  # Match this to the entrypoint (port exposed) deployed in the Base install!

storage:
  service:
    spec:
      persistentVolumeClaim:
        claimName: skaha-pvc # Match this label up with whatever was installed in the base install, or the desired PVC, or create dynamically provisioned storage.
```

Assuming your installation namespace is `skaha-system`:
```sh
$ helm repo update
$ helm -n skaha-system upgrade --install --values my-sshd-values.yaml sshd science-platform/sshd
```

### Install From Git source

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
