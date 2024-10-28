#!/bin/bash

# Variables
RESOURCE_GROUP="xtdblocalbench"
LOCATION="eastus"
STORAGE_ACCOUNT="xtdblocalbench23988"
CONTAINER_NAME="xtdblocalbench"
SERVICE_PRINCIPAL_NAME="xtdblocalsp"
MANAGED_IDENTITY_NAME="xtdblocalidentity"

# Create resource group
echo "Creating resource group..."
az group create --name $RESOURCE_GROUP --location $LOCATION
echo "Resource group: $RESOURCE_GROUP"

# Create storage account
echo "Creating storage account..."
az storage account create --name $STORAGE_ACCOUNT --resource-group $RESOURCE_GROUP --location $LOCATION --sku Standard_LRS
echo "Storage account: $STORAGE_ACCOUNT"

# Get storage account key
ACCOUNT_KEY=$(az storage account keys list --resource-group $RESOURCE_GROUP --account-name $STORAGE_ACCOUNT --query '[0].value' --output tsv)

# Create storage container
echo "Creating storage container..."
az storage container create --name $CONTAINER_NAME --account-name $STORAGE_ACCOUNT --account-key $ACCOUNT_KEY
echo "Storage container: $CONTAINER_NAME"

# Create service principal
echo "Creating service principal..."
SP_OUTPUT=$(az ad sp create-for-rbac --name $SERVICE_PRINCIPAL_NAME --role Contributor --scopes /subscriptions/$(az account show --query id --output tsv))

# Extract service principal details
SP_APP_ID=$(echo $SP_OUTPUT | jq -r .appId)
SP_PASSWORD=$(echo $SP_OUTPUT | jq -r .password)
SP_TENANT=$(echo $SP_OUTPUT | jq -r .tenant)

az role assignment create --assignee $SP_APP_ID --role "Contributor" --scope "/subscriptions/$(az account show --query id --output tsv)"
az role assignment create --assignee $SP_APP_ID --role "Storage Blob Data Contributor" --scope "/subscriptions/$(az account show --query id --output tsv)"

echo "Service Principal created successfully."
echo "App ID: $SP_APP_ID"
echo "Password: $SP_PASSWORD"
echo "Tenant: $SP_TENANT"

# Create user-assigned managed identity
echo "Creating user-assigned managed identity..."
az identity create --name $MANAGED_IDENTITY_NAME --resource-group $RESOURCE_GROUP --location $LOCATION

echo "Azure resources created successfully."