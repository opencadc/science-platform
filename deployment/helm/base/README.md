# Helm Chart for base objects of the CANFAR Science Platform

## Install

### From source

Installation depends on a working Kubernetes cluster version 1.23 or greater.

The base install also installs the Traefik proxy, which is needed by the Ingress when the Science Platform services are installed.

```sh
$ git clone https://github.com/opencadc/science-platform.git
$ cd science-platform/deployment/helm
$ helm install --dependency-update --values ./base/values.yaml <name> ./base
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install --dependency-update --values ./base/values.yaml canfar-science-platform-base ./base
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

### From the CANFAR repository

The Helm repository contains the current stable version as well.

```sh
$ helm repo add canfar-skaha-system https://images.canfar.net/chartrepo/skaha-system
$ helm repo update
$ helm install --dependency-update --values canfar-skaha-system/base/values.yaml canfar-science-platform-base canfar-skaha-system/base
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
skaha-nfs              Active   28m
skaha-system           Active   28m
skaha-workload         Active   28m
```

## NFS Service

This will install an NFS service ready to use.  It is an easy way to isolate the shared storage that is required by both the Skaha (https://ws-uv.canfar.net/skaha) and Cavern (Arc) (https://ws-uv.canfar.net/arc) web services.

```yaml
      - name: my-shared-volume
        nfs: 
          # URL for the NFS server
          # Can be accessed at the <helm-install-name>-nfs-server.skaha-system.svc.cluster.local hostname.
          server: "canfar-science-platform-base-nfs-server.skaha-system.svc.cluster.local" # Change this!
          path: /
```

### DNS on macOS

The Docker VM on macOS cannot mount the NFS by default as it cannot do name resolution in the cluster.  It first needs to know about the `kube-dns` IP.  e.g.:

```sh
$ kubectl -n kube-system get service kube-dns
NAME       TYPE        CLUSTER-IP   EXTERNAL-IP   PORT(S)                  AGE
kube-dns   ClusterIP   10.96.0.10   <none>        53/UDP,53/TCP,9153/TCP   4d23h
```

The `ClusterIP` needs to be known to the Docker VM's name resolution.  A simple way to do this is to mount the Docker VM root and modify it.  It will take effect immediately:

```sh
$ docker run --rm -it -v /:/vm-root alpine sh
$ echo "nameserver 10.96.0.10" >> /vm-root/etc/resolv.conf
$ cat /vm-root-etc/resolv.conf
# DNS requests are forwarded to the host. DHCP DNS options are ignored.
nameserver 192.168.65.7
nameserver 10.96.0.10
```
