(ns xtdb.metadata
  (:require [integrant.core :as ig]
            [xtdb.database :as db]
            [xtdb.util :as util])
  (:import xtdb.BufferPool
           (xtdb.metadata PageMetadata)))

(defmethod ig/prep-key ::metadata-manager [_ _]
  {:allocator (ig/ref :xtdb.database/allocator)
   :buffer-pool (ig/ref :xtdb/buffer-pool)})

(defmethod ig/init-key ::metadata-manager [_ {:keys [allocator, ^BufferPool buffer-pool, cache-size], :or {cache-size 128}}]
  (PageMetadata/factory allocator buffer-pool cache-size))

(defmethod ig/halt-key! ::metadata-manager [_ mgr]
  (util/try-close mgr))

(defn <-node ^xtdb.metadata.PageMetadata$Factory [node]
  (.getMetadataManager (db/<-node node)))
