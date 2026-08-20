{{- define "authorization-service.name" -}}authorization-service{{- end -}}
{{- define "authorization-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "authorization-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "authorization-service.labels" -}}
{{ include "authorization-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}