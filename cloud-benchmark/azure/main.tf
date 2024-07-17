terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "=3.111.0"
    }
  }

  backend "azurerm" {
      resource_group_name  = "benchmark-terraform"
      storage_account_name = "benchmarkterraform"
      container_name       = "benchmarkterraform"
      key                  = "terraform.tfstate"
  }
}

provider "azurerm" {
  features {}
}

variable "slack_webhook_url" {
  description = "The URL of the Slack webhook to send notifications to"
  type = string
  sensitive = true
}

resource "azurerm_resource_group" "cloud_benchmark" {
  name     = "cloud-benchmark-resources"
  location = "West Europe"
}

resource "azurerm_virtual_network" "cloud_benchmark" {
  name                = "cloud-benchmark-network"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  location            = azurerm_resource_group.cloud_benchmark.location
  address_space       = ["10.0.0.0/16"]
}

# Container App Configuration

resource "azurerm_container_registry" "acr" {
  name                = "cloudbenchmarkregistry"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  location            = azurerm_resource_group.cloud_benchmark.location
  sku                 = "Premium"
  admin_enabled       = true
}

resource "azurerm_log_analytics_workspace" "cloud_benchmark" {
  name                = "cloud-benchmark-log-analytics-workspace"
  location            = azurerm_resource_group.cloud_benchmark.location
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
}

resource "azurerm_container_app_environment" "cloud_benchmark" {
  name                       = "cloud-benchmark-container-app-environment"
  location                   = azurerm_resource_group.cloud_benchmark.location
  resource_group_name        = azurerm_resource_group.cloud_benchmark.name
  log_analytics_workspace_id = azurerm_log_analytics_workspace.cloud_benchmark.id
}


resource "azurerm_user_assigned_identity" "cloud_benchmark" {
  location            = azurerm_resource_group.cloud_benchmark.location
  name                = "cloud-benchmark-identity"
  resource_group_name = azurerm_resource_group.cloud_benchmark.name
}

resource "azurerm_role_assignment" "cloud_benchmark" {
  principal_id         = azurerm_user_assigned_identity.cloud_benchmark.principal_id
  role_definition_name = "AcrPull"
  scope                = azurerm_resource_group.cloud_benchmark.id
}

resource "azurerm_container_app" "cloud_benchmark" {
  name                         = "cloud-benchmark"
  resource_group_name          = azurerm_resource_group.cloud_benchmark.name
  container_app_environment_id = azurerm_container_app_environment.cloud_benchmark.id
  revision_mode                = "Single"

  identity {
    type = "UserAssigned"
    identity_ids = [
      azurerm_user_assigned_identity.cloud_benchmark.id
    ]
  }

  registry {
    server   = "cloudbenchmarkregistry.azurecr.io"
    identity = azurerm_user_assigned_identity.cloud_benchmark.id
  }

  ingress {
    external_enabled = true
    target_port = 5000
    transport = "auto"

    traffic_weight {
      latest_revision = true
      percentage = 100
    }
  }

  template {
    max_replicas = 1
    min_replicas = 1

    container {
      image = "cloudbenchmarkregistry.azurecr.io/xtdb-azure-bench:latest"
      name  = "cloud-benchmark"

      cpu    = 2
      memory = "4Gi"

      env {
        name  = "AUCTIONMARK_DURATION"
        value = "PT10M"
      }

      env {
        name = "AUCTIONMARK_SCALE_FACTOR"
        value = 0.1
      }

      env {
        name = "AUCTIONMARK_LOAD_PHASE"
        value = true
      }

      env {
        name = "AUCTIONMARK_LOAD_PHASE_ONLY"
        value = false
      }

      env {
        name = "CLOUD_PLATFORM_NAME"
        value = "Azure"
      }

      # env {
      #   name = "SLACK_WEBHOOK_URL"
      #   value = var.slack_webhook_url
      # }
    }
  }
}

# Blob Storage Configuration

# resource "azurerm_storage_account" "cloud_benchmark" {
#   name                     = "cloudbenchmark"
#   resource_group_name      = azurerm_resource_group.cloud_benchmark.name
#   location                 = azurerm_resource_group.cloud_benchmark.location
#   account_tier             = "Standard"
#   account_replication_type = "LRS"
# }

# resource "azurerm_storage_container" "cloud_benchmark" {
#   name                  = "cloudbenchmarkstorage"
#   storage_account_name  = azurerm_storage_account.cloud_benchmark.name
#   container_access_type = "private"
# }

# Event Hub Configuration

# resource "azurerm_eventhub_namespace" "cloud_benchmark" {
#   name                = "cloudBenchmarkNamespace"
#   location            = azurerm_resource_group.cloud_benchmark.location
#   resource_group_name = azurerm_resource_group.cloud_benchmark.name
#   sku                 = "Standard"
#   capacity            = 1

#   tags = {
#     environment = "Production"
#   }
# }

# resource "azurerm_eventhub" "cloud_benchmark" {
#   name                = "cloudBenchmarkHub"
#   namespace_name      = azurerm_eventhub_namespace.cloud_benchmark.name
#   resource_group_name = azurerm_resource_group.cloud_benchmark.name
#   partition_count     = 2
#   message_retention   = 1
# }