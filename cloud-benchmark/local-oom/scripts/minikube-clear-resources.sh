#!/usr/bin/env bash

set -e
(
  minikube kubectl -- delete jobs xtdb-multi-node-auctionmark --namespace xtdb-benchmark || true
  minikube kubectl -- delete deployment kafka-app --namespace xtdb-benchmark || true 
  minikube kubectl -- delete pvc xtdb-pvc-local-storage --namespace xtdb-benchmark || true
  minikube kubectl -- delete pvc kafka-pvc --namespace xtdb-benchmark || true

  echo Clearing Blob Store Container - xtdblocalbench...
  az storage blob delete-batch --account-name xtdblocalbench23988 --source xtdblocalbench
  echo Done
)
