# ADR-0006: User SSH access (sshpiper + dedicated SSH sessions)

## Status

Accepted, 2026-11-03.

## Context

Users need SSH and SSHFS to platform storage (`/home/<user>`, `/projects`) without shell access to arbitrary Notebook images. A separate SSH Job can mount cavern volumes with a controlled `sshd` image. Interactive sessions remain HTTP/TCP via Traefik; SSH requires a stable public TCP entry.

Shell **inside** a running Notebook/Desktop (any image) is out of scope for Skaha-launched sshd; users may use **tmate** from within that session (Lane B, documented separately).

## Decision

### Lane A — Platform SSH (Skaha + sshpiper)

- New session type **`ssh`**: `SessionType.SSH` — Service on port 22, no IngressRoute.
- **At most one** active SSH session Job per user (live Job check).
- SSH sessions **do not** count toward `maxUserSessions` (same quota class as headless).
- Image from **`SKAHA_SSH_SESSION_IMAGE`** (chart-configured); POST **`sshPublicKey`** on launch merges into a per-user Secret (`authorized_keys`); additional Register SSH flows patch that Secret.
- **`sshpiper.com/v1beta1` `Pipe`**, created and deleted by **Skaha** (Fabric8 generic client):
  - `spec.from.username` = POSIX username; `spec.from.authorized_keys_secret` references the user registered-keys Secret.
  - `spec.to.host` = session Service DNS name on port 22.
  - `metadata.ownerReferences` → SSH session Job (`controller: true`, `blockOwnerDeletion: true`).
  - Pipe and pipe Secrets live in the **workload namespace** (same namespace as the Job).
- Helm deploys sshpiper (Kubernetes plugin), Pipe CRD, host keys, LoadBalancer/DNS **`SKAHA_SSH_HOSTNAME`**, and NetworkPolicy (sshpiper → session pods :22).
- Skaha RBAC: `create`, `get`, `delete` on `pipes.sshpiper.com` and additional Secrets for pipe/upstream keys.

### Lane B — In-session shell (tmate)

- Documented runbook only for v1; no Skaha API changes.
- See [../runbooks/tmate-in-session.md](../runbooks/tmate-in-session.md).

## Consequences

- Operators must install Pipe CRD and sshpiper; external DNS for SSH hostname.
- Users have two concepts: **SSH session** (files via sshpiper) vs **tmate** (shell inside a running session).
- Ephemeral `/scratch` on the SSH Job is tied to the SSH session ID, not a Notebook session.

## References

- [CADC SSH access plan](https://github.com/opencadc/science-platform) (internal)
- [sshpiper](https://github.com/tg123/sshpiper)
- [ADR-0005](0005-fabric8-kubernetes-client.md)
