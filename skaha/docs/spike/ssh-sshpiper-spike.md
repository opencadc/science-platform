# Spike: SSH session + sshpiper + SFTP/SSHFS

Manual validation checklist before/after MVP merge. Run on kind/minikube with chart `sshpiper` enabled.

## Prerequisites

- `sshpiper` CRD installed (`helm/templates/sshpiper-crd.yaml`).
- sshpiper Deployment watching the workload namespace.
- One `Pipe` per user: `spec.from.username` = POSIX username; `spec.to.host` = `skaha-ssh-svc-<sessionId>:22`.

## Steps

1. POST Skaha session `type=ssh` with `sshPublicKey` (OpenSSH one-line public key).
2. Confirm Job, Service (port 22), Pipe (`metadata.ownerReferences` → Job), per-user registered-keys Secret (`skaha-ssh-user-keys-<user>`), and per-session upstream/host-auth Secrets exist in the workload namespace.
3. `ssh -i <private> <username>@<ssh.host>` through sshpiper LoadBalancer/DNS.
4. `sftp <username>@<ssh.host>` — SFTP subsystem must respond.
5. `sshfs <username>@<ssh.host>:/cavern/home/<user> ~/mnt` — same path as session `HOME` parent layout (match [launch-ssh.yaml](../../../helm/skaha-config/launch-ssh.yaml) mounts).
6. Delete session; confirm Pipe disappears (GC via ownerReference) and Service/Job are gone.

## Network policy

- Egress: sshpiper → workload namespace TCP 22 (see [sshpiper-networkpolicy.yaml](../../../helm/templates/sshpiper-networkpolicy.yaml)).
- No inbound to session pods except from sshpiper (optional hardening).

## Expected outcome

SSH and SFTP/SSHFS work through a single public host; routing is username-only with at most one SSH session per user enforced by Skaha.
