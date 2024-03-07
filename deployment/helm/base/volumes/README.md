# Example Volumes

These are example PersistentVolumeClaims that could be used *AFTER* the `base` Helm Chart is installed.

Creating your PVCs can be based off of these, or create your own.  The `namespace` property is important as the PVC for Skaha and the PVC for the User Sessions are required
to be in the `skaha-system` and `skaha-workload` namespaces respectively.
