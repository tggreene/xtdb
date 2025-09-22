variable "location" {
  description = "Azure region, e.g., westeurope"
  type        = string
}

variable "resource_group_name" {
  description = "Resource group name"
  type        = string
}

variable "workspace_name" {
  description = "Log Analytics workspace name"
  type        = string
}

variable "table_name" {
  description = "Log Analytics custom table name (must end with _CL)"
  type        = string
  default     = "XTDBBenchmark_CL"
}

variable "dce_name" {
  description = "Data Collection Endpoint name"
  type        = string
}

variable "dcr_name" {
  description = "Data Collection Rule name"
  type        = string
}

variable "sender_principal_id" {
  description = "Object ID of the principal to grant 'Monitoring Data Collection Rule Data Sender' on the DCR"
  type        = string
  default     = ""
}

# Action Group (Slack) configuration
variable "action_group_name" {
  description = "Name for the Azure Monitor Action Group"
  type        = string
  default     = "xtdb-benchmark-alerts"
}

variable "action_group_short_name" {
  description = "Short name for Action Group"
  type        = string
  default     = "xtdbbench"
}

variable "slack_webhook_url" {
  description = "Slack webhook URL for alerts (optional). If empty, no webhook receiver is created."
  type        = string
  default     = ""
  sensitive   = true
}

# Alert configuration
variable "alert_name" {
  description = "Name of the scheduled query alert"
  type        = string
  default     = "xtdb-benchmark-slow-alert"
}

variable "alert_severity" {
  description = "Alert severity (0=Sev0, 4=Sev4)"
  type        = number
  default     = 3
}

variable "alert_enabled" {
  description = "Whether the scheduled query alert is enabled"
  type        = bool
  default     = true
}

variable "alert_evaluation_frequency" {
  description = "How often to evaluate the alert (ISO 8601 duration, e.g. PT1H)"
  type        = string
  default     = "PT1H"
}

variable "alert_window_duration" {
  description = "Time window for the alert query (ISO 8601 duration)"
  type        = string
  default     = "PT24H"
}

variable "alert_baseline_n" {
  description = "Number of previous runs to average for the baseline"
  type        = number
  default     = 20
}

variable "alert_threshold_fraction" {
  description = "Threshold fraction for slowdown detection (e.g., 0.02 for 2%)"
  type        = number
  default     = 0.02
}
