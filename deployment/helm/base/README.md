# Helm Chart for base objects of the CANFAR Science Platform

## Install

Installation depends on a working Kubernetes cluster >= 1.23.

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install --dependency-update --create-namespace --namespace skaha-system <name> ./base
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install --dependency-update --create-namespace --namespace skaha-system canfar-science-platform-base ./base
```
This will create the core namespace (`skaha-system`), and install the Traefik proxy dependency.  Expected output:
```
NAME: canfar-science-platform-base
LAST DEPLOYED: <Timestamp e.g. Fri Jun 30 10:39:04 2023>
NAMESPACE: skaha-system
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

After the install, there should exist the necessary Namespaces and Objects.  See the Namespaces:

```sh
$ kubectl get namespaces
NAME                   STATUS   AGE
...
cadc-harbor            Active   28m
cadc-loki              Active   28m
cadc-openharbor        Active   28m
cadc-sssd              Active   28m
nvidia-device-plugin   Active   28m
skaha-system           Active   28m
skaha-workload         Active   28m
```
