kubectl -n skaha-system create secret generic cavern-sshd-priv1-secret --from-file=./ssh_host_ecdsa_key
kubectl -n skaha-system create secret generic cavern-sshd-priv2-secret --from-file=./ssh_host_ed25519_key
kubectl -n skaha-system create secret generic cavern-sshd-priv3-secret --from-file=./ssh_host_rsa_key
