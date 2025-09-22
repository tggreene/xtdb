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

# Alert configuration (slow run vs baseline)
alert_name                 = "xtdb-benchmark-slow-alert"
alert_severity             = 3          # 0..4
alert_enabled              = true
alert_evaluation_frequency = "PT1H"     # evaluate hourly
alert_window_duration      = "P1D"      # lookback window
alert_baseline_n           = 20         # previous N runs for baseline
alert_threshold_fraction   = 0.02       # 2% slower than baseline