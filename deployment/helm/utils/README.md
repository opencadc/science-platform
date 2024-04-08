# Science Platform Helm Utility library (0.1.0)

A small [Helm Library Chart](https://helm.sh/docs/topics/library_charts/) to provide common utility functions to other Charts.

## getSecretKeyValue

The `getSecretKeyValue` function in the [_get-secret-key-value.yaml](./templates/_get-secret-key-value.yaml) file contains a function to read a Kubernetes Secret, and extract a value for the given `key`.

### Example

```yaml
{{- $clientSecret := include "getSecretKeyValue" (list $existingSecretName "clientSecret" $namespace) -}}
```