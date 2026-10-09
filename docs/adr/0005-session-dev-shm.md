# ADR-0005: Session `/dev/shm` sized to memory limit

## Status

Accepted

## Context

Kubernetes gives each container a ~64Mi `/dev/shm`. Scientific session images
(POSIX shared memory, Python `multiprocessing`, GPU runtimes) often need more.
A memory-backed `emptyDir` mounted at `/dev/shm` is the usual fix. The volume
is tmpfs: unused it costs nothing; used pages count against the same cgroup as
the session process.

## Decision

- Every session type mounts `dshm-volume` at `/dev/shm` via
  `skaha.session.commonVolumes` and `skaha.session.commonVolumeMounts`.
- The volume is an `emptyDir` with `medium: Memory` and
  `sizeLimit: "${software.limits.ram}"` (the container memory limit, not the
  request).
- There is no Helm enable/disable flag.
- Sessions stay unprivileged and do not share the host IPC namespace; the mount
  stays inside the pod.

## Consequences

- Filling `/dev/shm` plus RSS can OOM-kill that session. Recovery is a new
  session with a larger RAM request; the killed session cannot be resized.
- Desktop and Firefly still use hardcoded 4Gi container memory limits. Their
  `/dev/shm` `sizeLimit` follows `${software.limits.ram}` and may exceed 4Gi
  until those launch specs use the same placeholder.

## References

- [`../../helm/templates/session-volumes.yaml`](../../helm/templates/session-volumes.yaml)
- [`../../helm/templates/session-volumes-mounts.yaml`](../../helm/templates/session-volumes-mounts.yaml)
