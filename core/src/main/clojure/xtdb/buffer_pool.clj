(ns xtdb.buffer-pool
  (:require [integrant.core :as ig]
            [xtdb.database :as db]
            [xtdb.node :as xtn]
            [xtdb.util :as util])
  (:import (xtdb BufferPool)
           (xtdb.api.storage Storage Storage$Factory)
           xtdb.api.Xtdb$Config))

(set! *unchecked-math* :warn-on-boxed)

(defmethod xtn/apply-config! ::local [^Xtdb$Config config _ {:keys [path]}]
  (.storage config (Storage/localStorage (util/->path path))))

(defmulti ->object-store-factory
  #_{:clj-kondo/ignore [:unused-binding]}
  (fn [tag opts]
    (when-let [ns (namespace tag)]
      (doseq [k [(symbol ns)
                 (symbol (str ns "." (name tag)))]]

        (try
          (require k)
          (catch Throwable _))))

    tag))

(defmethod ->object-store-factory :in-memory [_ opts] (->object-store-factory :xtdb.object-store-test/memory-object-store opts))
(defmethod ->object-store-factory :s3 [_ opts] (->object-store-factory :xtdb.aws/s3 opts))
(defmethod ->object-store-factory :google-cloud [_ opts] (->object-store-factory :xtdb.gcp/object-store opts))
(defmethod ->object-store-factory :azure [_ opts] (->object-store-factory :xtdb.azure/object-store opts))

(defmethod xtn/apply-config! ::remote [^Xtdb$Config config _ {:keys [object-store]}]
  (.storage config (Storage/remoteStorage (let [[tag opts] object-store]
                                            (->object-store-factory tag opts)))))

(defmethod xtn/apply-config! ::storage [config _ [tag opts]]
  (xtn/apply-config! config
                     (case tag
                       :in-memory ::in-memory
                       :local ::local
                       :remote ::remote)
                     opts))

(defmethod ig/prep-key :xtdb/buffer-pool [_ {:keys [base factory]}]
  {:base base, :factory factory
   :allocator (ig/ref :xtdb.database/allocator)})

(defmethod ig/init-key :xtdb/buffer-pool [_ {{:keys [meter-registry mem-cache disk-cache]} :base, :keys [allocator ^Storage$Factory factory]}]
  (.open factory allocator mem-cache disk-cache meter-registry Storage/VERSION))

(defmethod ig/halt-key! :xtdb/buffer-pool [_ ^BufferPool buffer-pool]
  (util/close buffer-pool))

(defn <-node ^xtdb.BufferPool [node]
  (.getBufferPool (db/<-node node)))
