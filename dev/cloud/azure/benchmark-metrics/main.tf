terraform {
  required_version = ">= 1.5.0"

  backend "azurerm" {
    resource_group_name  = "xtdb-bench-terraform-state"
    storage_account_name = "xtdbbenchstate"
    container_name      = "benchmark-metrics-tfstate"
    key                 = "benchmark-metrics.terraform.tfstate"
  }

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 4.44.0"
    }
    azapi = {
      source  = "azure/azapi"
      version = ">= 1.13.0"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = "91804669-c60b-4727-afa2-d7021fe5055b"
}

provider "azapi" {}

locals {
  stream_name = "Custom-${var.table_name}"
}

# Optional subscription context
data "azurerm_client_config" "current" {}

# Resource Group (optional)
resource "azurerm_resource_group" "rg" {
  name     = var.resource_group_name
  location = var.location
}

# Log Analytics Workspace
resource "azurerm_log_analytics_workspace" "law" {
  name                = var.workspace_name
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  depends_on = [
    azurerm_resource_group.rg
  ]
}

# Custom table for benchmarks (AzureRM v4)
resource "azurerm_log_analytics_workspace_table" "bench" {
  name                    = var.table_name
  workspace_id            = azurerm_log_analytics_workspace.law.id
  retention_in_days       = 30
  total_retention_in_days = 90
}

# Data Collection Endpoint (DCE)
resource "azurerm_monitor_data_collection_endpoint" "dce" {
  name                = var.dce_name
  location            = var.location
  resource_group_name = var.resource_group_name
}

# Data Collection Rule (DCR)
resource "azurerm_monitor_data_collection_rule" "dcr" {
  name                        = var.dcr_name
  location                    = var.location
  resource_group_name         = var.resource_group_name
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.dce.id

  destinations {
    log_analytics {
      name                  = "laDest"
      workspace_resource_id = azurerm_log_analytics_workspace.law.id
    }
  }

  data_flow {
    streams      = [local.stream_name]
    destinations = ["laDest"]
    output_stream = local.stream_name
    transform_kql = <<-KQL
      source | project TimeGenerated=todatetime(ts), run_id=tostring(run_id), git_sha=tostring(git_sha), repo=tostring(repo), benchmark=tostring(benchmark), step=tostring(step), node_id=tostring(node_id), metric=tostring(metric), value=todouble(value), unit=tostring(unit), ts=todatetime(ts), params=todynamic(params)
    KQL
  }

  # Associate the stream with the LA table
  stream_declaration {
    stream_name = local.stream_name
    column {
      name = "run_id"
      type = "string"
    }
    column {
      name = "git_sha"
      type = "string"
    }
    column {
      name = "repo"
      type = "string"
    }
    column {
      name = "benchmark"
      type = "string"
    }
    column {
      name = "step"
      type = "string"
    }
    column {
      name = "node_id"
      type = "string"
    }
    column {
      name = "metric"
      type = "string"
    }
    column {
      name = "value"
      type = "real"
    }
    column {
      name = "unit"
      type = "string"
    }
    column {
      name = "ts"
      type = "datetime"
    }
    column {
      name = "params"
      type = "dynamic"
    }
  }
}

resource "azurerm_role_assignment" "dcr_sender" {
  scope                = azurerm_monitor_data_collection_rule.dcr.id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = var.sender_principal_id
}

# Logic App: Relay Azure Monitor alerts to Slack (Incoming Webhook)
resource "azurerm_logic_app_workflow" "bench_alert_relay" {
  name                = "xtdb-bench-alert-relay"
  location            = var.location
  resource_group_name = var.resource_group_name
  enabled             = true
}

resource "azurerm_logic_app_trigger_http_request" "bench_alert_trigger" {
  name         = "http_trigger"
  logic_app_id = azurerm_logic_app_workflow.bench_alert_relay.id
  schema       = <<SCHEMA
{
  "type": "object"
}
SCHEMA
}

resource "azurerm_logic_app_action_http" "bench_alert_post_to_slack" {
  name         = "PostToSlack"
  logic_app_id = azurerm_logic_app_workflow.bench_alert_relay.id
  method       = "POST"
  uri          = var.slack_webhook_url

  headers = {
    "Content-Type" = "application/json"
  }

  body = jsonencode({
    attachments = [
      {
        color       = "#D32F2F"
        title       = "@{coalesce(triggerBody()?['data']?['essentials']?['alertRule'], 'Alert')}"
        title_link  = "@{concat('https://portal.azure.com/#blade/Microsoft_Azure_Monitoring/AlertDetailsTemplateBlade/alertId/', uriComponent(triggerBody()?['data']?['essentials']?['alertId']))}"
        text        = "@{concat('*Severity:* ', coalesce(string(triggerBody()?['data']?['essentials']?['severity']), 'N/A'), '\n', '*Description:* ', coalesce(triggerBody()?['data']?['essentials']?['description'], ''), '\n', '*Target:* ', coalesce(triggerBody()?['data']?['essentials']?['alertTargetIDs'][0], ''))}"
        mrkdwn_in   = ["text"]
      }
    ]
  })
}

resource "azapi_resource_action" "bench_alert_relay_cb" {
  type        = "Microsoft.Logic/workflows/triggers@2019-05-01"
  resource_id = "${azurerm_logic_app_workflow.bench_alert_relay.id}/triggers/${azurerm_logic_app_trigger_http_request.bench_alert_trigger.name}"
  action      = "listCallbackUrl"
  method      = "POST"
  response_export_values = ["value"]
}

# Action Group for alerts (Slack via webhook)
resource "azurerm_monitor_action_group" "bench_slack" {
  name                = var.action_group_name
  resource_group_name = var.resource_group_name
  short_name          = var.action_group_short_name

  logic_app_receiver {
    name                    = "slack-relay"
    resource_id             = azurerm_logic_app_workflow.bench_alert_relay.id
    callback_url            = azapi_resource_action.bench_alert_relay_cb.output.value
    use_common_alert_schema = true
  }

  email_receiver {
    name           = "tim"
    email_address  = "tim@juxt.pro"
  }
}

# Scheduled query alert: detect latest run slower than baseline
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "bench_slow_alert" {
  name                = var.alert_name
  resource_group_name = var.resource_group_name
  location            = var.location
  severity            = var.alert_severity
  enabled             = var.alert_enabled

  evaluation_frequency = var.alert_evaluation_frequency
  window_duration      = var.alert_window_duration

  scopes = [azurerm_log_analytics_workspace.law.id]

  criteria {
    query = <<-KQL
      let N = toint(${var.alert_baseline_n});
      let threshold = todouble(${var.alert_threshold_fraction});
      let latestBenchmark =
          ${var.table_name}
          | where step == "overall" and metric == "duration_ms"
          | summarize arg_max(TimeGenerated, *)
          | extend current_ms = todouble(value), k = 1;
      let baseline =
          ${var.table_name}
          | where step == "overall" and metric == "duration_ms"
          | order by TimeGenerated desc
          | top N+1 by TimeGenerated desc
          | top N by TimeGenerated asc
          | order by TimeGenerated desc
          | summarize baseline_ms = avg(todouble(value))
          | extend k = 1;
      latestBenchmark
        | join kind=inner baseline on k
        | extend delta_pct = (current_ms - baseline_ms) / baseline_ms
        | where delta_pct > threshold
        | project TimeGenerated
    KQL
    time_aggregation_method = "Count"
    operator         = "GreaterThan"
    threshold        = 0
    failing_periods {
      number_of_evaluation_periods = 1
      minimum_failing_periods_to_trigger_alert = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.bench_slack.id]
  }

  description = "Alert when latest benchmark duration exceeds baseline by configured threshold"

  depends_on = [
    azurerm_monitor_data_collection_rule.dcr,
    azurerm_log_analytics_workspace_table.bench
  ]
}
