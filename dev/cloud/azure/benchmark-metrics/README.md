# Benchmark Metrics (Azure) — Terraform

This Terraform setup provisions Azure Monitor Logs ingestion for benchmark metrics.

Resources:
- Log Analytics Workspace (`var.workspace_name`)
- Custom Log Analytics Table (`var.table_name`, default `XTDBBenchmark_CL`)
- Data Collection Endpoint (DCE)
- Data Collection Rule (DCR) with transform KQL mapping input JSON to the table
- Optional role assignment: "Monitoring Metrics Publisher" on the DCR

## Caveats

- Log Analytics custom table lifecycle is managed manually. Terraform will reference and track the table (`azurerm_log_analytics_workspace_table`) but cannot author the schema end-to-end. Use Azure CLI to create/update/delete the table and its columns as needed; then import or let Terraform track its existence.

  Examples:
  ```bash
  # Create a custom table (name must end with _CL)
  az monitor log-analytics workspace table create \
    --resource-group "$RG" \
    --workspace-name "$LAW" \
    --name "$TABLE" \
    --plan Analytics \
      --columns \
        "TimeGenerated=datetime" \
        "run_id=string" \
        "git_sha=string" \
        "benchmark=string" \
        "repo=string" \
        "step=string" \
        "node_id=string" \
        "metric=string" \
        "value=real" \
        "unit=string" \
        "ts=datetime" \
        "params=dynamic"


  # Update retention
  az monitor log-analytics workspace table update \
    --resource-group "$RG" \
    --workspace-name "$LAW" \
    --name "$TABLE" \
    --retention-time 30 --total-retention-time 90

  # Delete table
  az monitor log-analytics workspace table delete \
    --resource-group "$RG" \
    --workspace-name "$LAW" \
    --name "$TABLE" -y
  ```

Outputs:
- `dce_ingest_endpoint` — e.g. https://<dce>.<region>.ingest.monitor.azure.com
- `dcr_immutable_id` — use in the Logs Ingestion API path
- `stream_name` — e.g. `Custom-XTDBBenchmark_CL`

Input payload format (array of records):
```json
[
  {
    "run_id": "...",
    "git_sha": "...",
    "repo": "owner/repo",
    "benchmark": "tpch",
    "step": "overall",
    "node_id": "n1",
    "metric": "duration_ms",
    "value": 123.45,
    "unit": "ms",
    "ts": "2025-01-01T00:00:00Z",
    "params": {"scaleFactor": 1}
  }
]
```

Transform KQL in the DCR maps `ts` -> `TimeGenerated`, casts types, and projects all fields including `repo`.

## Usage

Use in GitHub Actions:
- DCE endpoint: `outputs.dce_ingest_endpoint`
- DCR immutable ID: `outputs.dcr_immutable_id`
- Stream: `outputs.stream_name`

Acquire token and post:
```bash
ACCESS_TOKEN=$(az account get-access-token --resource https://monitor.azure.com --query accessToken -o tsv)
URL="${dce_ingest_endpoint%/}/dataCollectionRules/${dcr_immutable_id}/streams/${stream_name}?api-version=2023-01-01"
curl -sS -X POST "$URL" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  --data-binary @metrics.json
```
