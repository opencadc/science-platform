{{/* vim: set filetype=mustache: */}}
{{/*
Generate a new secret field in the given secret if it does not already exist.
*/}}
{{- define "gen.secret" -}}
  {{- $clientName := .clientName }}
  {{- $namespace := .namespace }}
  {{- $secretName := .secretName }}
  {{- $clientAPISecretFieldName := printf "%s-%s" $clientName "api-key" }}
  # retrieve the secret data using lookup function and when not exists, return an empty dictionary / map as result
  {{- $secretObj := (lookup "v1" "Secret" $namespace $secretName) | default dict }}
  {{- $secretData := (get $secretObj "data") | default dict }}
  # set $apiSecret to existing secret data or generate a random one when not exists
  {{- $apiSecret := (get $secretData $clientAPISecretFieldName) | default (randAlphaNum 32 | b64enc) }}
  {{ $clientAPISecretFieldName }}: {{ $apiSecret | quote }}
{{- end -}}