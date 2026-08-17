{{- define "notification-service.name" -}}
notification-service
{{- end -}}

{{- define "notification-service.labels" -}}
app.kubernetes.io/name: {{ include "notification-service.name" . }}
app.kubernetes.io/part-of: hooshix
{{- end -}}

{{- define "notification-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "notification-service.name" . }}
{{- end -}}
