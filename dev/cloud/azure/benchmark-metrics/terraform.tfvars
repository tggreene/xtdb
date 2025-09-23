# Core resources
location            = "westeurope"
resource_group_name = "xtdb-benchmark-metrics"
workspace_name      = "xtdb-benchmark-metrics"
dce_name            = "xtdb-benchmark-metrics"
dcr_name            = "xtdb-benchmark-metrics"

# Optional: custom table name (must end with _CL)
table_name = "XTDBBenchmark_CL"

# Principal allowed to post via Logs Ingestion API
# NOTE: set to the Object ID of the principal that will send metrics (e.g. your SP or workload identity)
sender_principal_id = "0995c238-9646-4014-be4d-487ff8300975"

# Action Group (Slack)
action_group_name       = "xtdb-benchmark-alerts"
action_group_short_name = "xtdbbench"
# set via TF_VAR_slack_webhook_url = "https://hooks.slack.com/services/XXX/YYY/ZZZ"
# slack_webhook_url       = "https://hooks.slack.com/services/XXX/YYY/ZZZ"

# Anomaly detection parameters (used by Logic App)
anomaly_logic_app_name     = "xtdb-benchmark-anomaly"
anomaly_schedule_frequency = "Minute"
anomaly_schedule_interval  = 5
anomaly_baseline_n         = 20   # previous N runs for baseline
anomaly_sigma              = 2    # 2σ threshold
anomaly_scale_factor       = 0.5
anomaly_timespan           = "P30D"

missing_alert_evaluation_frequency = "P1D"
missing_alert_window_duration      = "P2D"
missing_alert_enabled              = true
missing_alert_severity             = 2

dashboard_name = "xtdb-benchmark-dashboard"