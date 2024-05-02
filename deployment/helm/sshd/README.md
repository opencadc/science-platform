# Helm Chart for the Science Platform SSHD service

See the [Deployment Guide](../README.md) for a better idea of a full system.

## Preparation

To faciliate password-less logins, please upload a Public Key to your Home directory in the `.ssh/authorized_key` file.  The easiest way to do that
is to create a local `authorized_keys` file, or you may use your existing one if it contains your Public Key already.  If you're creating a new one, simply put the contents
of your Public Key into the `authorized_keys` file:

```sh
$ cat ~/.ssh/id_rsa.pub > authorized_keys
```

Then create a folder in the called `.ssh` in your `/home/<username>` folder.  It's quickest to use the Storage UI to do that.  Assuming your storage is called `cavern` and your username is `exampleuser`:

```sh
$ open https://example.org/storage/cavern/list/home/exampleuser
```

Once authenticated, ensure the `.ssh` folder exists with the `+ Add` -> `Folder` pulldown.

Then navigate into that folder, and uplaod the newly created `authorized_keys` file:

```sh
$ open https://example.org/storage/cavern/list/home/exampleuser/.ssh
```

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

Assuming your installation namespace is `skaha-system` (it should be the same as the other installations):
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
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml <name> ./sshd
```

Where `<name>` is the name of this installation.  Example:
```sh
$ helm install -n skaha-system --dependency-update --values my-values-local.yaml my-sshd ./sshd
```
This will install the SSH Daemon service and any necessary Ingress.
```
NAME: sshd
LAST DEPLOYED: <Timestamp e.g. The May 02 13:10:22 2024>
STATUS: deployed
REVISION: 1
TEST SUITE: None
```

## Verification

From a terminal:
```sh
$ ssh -p 64022 example.org
```