{{- /*
Generate a new key field in the given secret if it does not already exist.  The .pub field is intentionally
left empty as Helm cannot generate it.  We maintain the private keys though, so the user won't be bothered
by the constant host name issue.
*/}}
{{- define "sshd.gen.key" -}}
  {{- $namespace := .namespace }}
  {{- $secretName := .secretName }}
  {{- $secretFieldName := .secretFieldName }}
  {{- $keyType := .keyType }}
  # retrieve the secret data using lookup function and when not exists, return an empty dictionary / map as result
  {{- $secretObj := (lookup "v1" "Secret" $namespace $secretName) | default dict }}
  {{- $secretData := (get $secretObj "data") | default dict }}
  # set $apiSecret to existing secret data or generate a random one when not exists
  {{- $apiSecret := (get $secretData $secretFieldName) | default (genPrivateKey $keyType | b64enc) }}
  {{ $secretFieldName }}: {{ $apiSecret }}
{{- end -}}
