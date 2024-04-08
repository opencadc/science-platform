# Science Platform Helm Utility library (0.1.0)

A small [Helm Library Chart](https://helm.sh/docs/topics/library_charts/) to provide common utility functions to other Charts.

## Install

Add to your Chart dependencies from within the `helm` folder:

```yaml
  - name: "utils"
    version: "^0.1.0"
    repository: "file://../utils"
```

## Functions

### getSecretKeyValue

The `getSecretKeyValue` function in the [_get-secret-key-value.yaml](./templates/_get-secret-key-value.yaml) file contains a function to read a Kubernetes Secret, and extract a value for the given `key`.

#### Example

```yaml
{{- $clientSecret := include "getSecretKeyValue" (list $existingSecretName "clientSecret" $namespace) -}}
```