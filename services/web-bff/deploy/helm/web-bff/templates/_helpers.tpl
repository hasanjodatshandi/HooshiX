{{- define "web-bff.name" -}}web-bff{{- end -}}
{{- define "web-bff.selectorLabels" -}}
app.kubernetes.io/name: {{ include "web-bff.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "web-bff.labels" -}}
{{ include "web-bff.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}