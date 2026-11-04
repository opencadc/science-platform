# tmate inside a running session (Lane B)

Use this when you need an **interactive shell in the same environment** as a running Notebook or Desktop session (installed packages, container filesystem). This is **not** the platform SSH session (`type=ssh` via sshpiper).

## Steps

1. Open a terminal in the running session (Notebook terminal or Desktop).
2. Install or use `tmate` if present in the image; otherwise use a site-provided module or self-hosted binary.
3. Run `tmate` (or `tmate -F` for foreground). Note the `ssh …` connection string tmate prints.
4. Connect from your laptop with that command.

## Site requirements

- **Egress** from session pods to the tmate coordinator (tmate.io or your self-hosted server) on HTTPS/SSH. Adjust [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/) if sessions are locked down (see `helm/templates/metrics-networkpolicy.yaml` for patterns).
- **Policy**: third-party tmate.io vs self-hosted tmate for data residency.
- **Support**: CANFAR/skaha does not create or tear down tmate sessions; users manage sharing and revocation via tmate.

## SSHFS

tmate gives a shell in the **session pod**. Files visible there include mounts already in that pod (home, projects). It does **not** replace Lane A SSH (`ssh.example.com` via Skaha `type=ssh`) for direct SSHFS from your laptop to cavern paths through sshpiper.

## References

- [tmate](https://github.com/tmate-io/tmate)
- [ADR-0006](../adr/0006-user-ssh-access.md)
