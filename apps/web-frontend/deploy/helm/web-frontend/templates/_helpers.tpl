{{- define "web-frontend.name" -}}web-frontend{{- end -}}
{{- define "web-frontend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "web-frontend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "web-frontend.labels" -}}
{{ include "web-frontend.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
