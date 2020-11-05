* Creating kubectl configuration for apache

1. Create and save file config/k8s-config from the following template:

```
apiVersion: v1
kind: Config
users:
- name: skaha
  user:
    token: <token base64 string>
clusters:
- cluster:
    certificate-authority-data: <ca.crt base64 string>
    server: <server>
  name: <cluster-name>
contexts:
- context:
    cluster: <cluster-name>
    user: skaha
  name: skaha-wsproxy
current-context: skaha-wsproxy
```

1. Fill in the value for <ca.crt base64 string> with value in ~/.kube/config

1. Temporarily create the (currently incomplete) config with `create-config.sh`

1. Fill in the value of <token base64 string> and <server> by:
  - Deploying arcade-wsproxy with `deploy-arcade-wsproxy.sh`
  - Connect to the pod with `kubectl -n skaha-system <podID> -- bash`
  - Grab the value from /var/run/secrets/kubernetes.io/serviceaccount/token 
  - Grab the value for <server> with `env | grep KUBERNETES_PORT_443_TCP`, but change `tcp` to `https`
  - exit the container and terminate: `kubectl delete deployment arcade-wsproxy`

1. Fill in the values for <cluster-name> with the name of your cluster (eg k9s-dev)

1. Delete the temporary configmap with `kubectl delete cm skaha-wsproxy-config`

1. Create the config with `create-config.sh`

1. Deploy skaha-wsproxy again with `deploy-skaha-wsproxy.sh`
