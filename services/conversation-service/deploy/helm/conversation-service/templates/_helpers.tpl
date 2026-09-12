{{- define "conversation-service.name" -}}
conversation-service
{{- end -}}

{{- define "conversation-service.labels" -}}
app.kubernetes.io/name: {{ include "conversation-service.name" . }}
app.kubernetes.io/part-of: hooshix
{{- end -}}

{{- define "conversation-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "conversation-service.name" . }}
{{- end -}}
