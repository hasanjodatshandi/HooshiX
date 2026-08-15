{{- define "compromised-password-service.name" -}}
compromised-password-service
{{- end -}}

{{- define "compromised-password-service.labels" -}}
app.kubernetes.io/name: {{ include "compromised-password-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "compromised-password-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "compromised-password-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
