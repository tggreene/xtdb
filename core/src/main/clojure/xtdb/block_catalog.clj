(ns xtdb.block-catalog
  (:require [integrant.core :as ig]
            [xtdb.database :as db]
            [xtdb.serde :as serde]
            [xtdb.time :as time])
  (:import (xtdb.block.proto Block TxKey)
           xtdb.catalog.BlockCatalog))

(defmethod ig/prep-key :xtdb/block-catalog [_ _]
  {:buffer-pool (ig/ref :xtdb/buffer-pool)})

(defmethod ig/init-key :xtdb/block-catalog [_ {:keys [buffer-pool]}]
  (BlockCatalog. buffer-pool))

(defn <-node ^xtdb.catalog.BlockCatalog [node]
  (.getBlockCatalog (db/<-node node)))

(defn- <-TxKey [^TxKey tx-key]
  (serde/->TxKey (.getTxId tx-key) (time/micros->instant (.getSystemTime tx-key))))

(defn <-Block [^Block block]
  {:block-idx (.getBlockIndex block)
   :latest-completed-tx (<-TxKey (.getLatestCompletedTx block))
   :latest-processed-msg-id (.getLatestProcessedMsgId block)
   :table-names (set (.getTableNamesList block))})
