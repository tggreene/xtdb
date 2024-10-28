#!/usr/bin/env bash

# Variables
RESOURCE_GROUP="xtdblocalbench"
STORAGE_ACCOUNT="xtdblocalbench"
SERVICE_PRINCIPAL_NAME="xtdblocalsp"
MANAGED_IDENTITY_NAME="xtdblocalidentity"

# Delete service principal
echo "Deleting service principal..."
SP_ID=$(az ad sp list --display-name $SERVICE_PRINCIPAL_NAME --query '[0].appId' --output tsv)
if [ -n "$SP_ID" ]; then
  az ad sp delete --id $SP_ID
fi

# Delete user-assigned managed identity
echo "Deleting user-assigned managed identity..."
IDENTITY_ID=$(az identity show --name $MANAGED_IDENTITY_NAME --resource-group $RESOURCE_GROUP --query 'id' --output tsv)
if [ -n "$IDENTITY_ID" ]; then
  az identity delete --ids $IDENTITY_ID
fi

# Delete storage account
echo "Deleting storage account..."
az storage account delete --name $STORAGE_ACCOUNT --resource-group $RESOURCE_GROUP --yes

# Delete resource group
echo "Deleting resource group..."
az group delete --name $RESOURCE_GROUP --yes --no-wait

echo "Azure resources deleted successfully."