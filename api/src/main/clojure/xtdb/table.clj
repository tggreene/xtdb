(ns ^:no-doc xtdb.table
  (:require [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.transit :as transit])
  (:import [xtdb.table TableRef]
           xtdb.util.NormalForm))

(defmethod print-method TableRef [^TableRef ref, ^java.io.Writer w]
  (.write w (format "#xt/table %s"
                    (let [schema (.getSchemaName ref)]
                      (if (= schema "public")
                        (symbol (.getTableName ref))
                        (symbol schema (.getTableName ref)))))))

(defmethod print-dup TableRef [ref w] (print-method ref w))
(defmethod pp/simple-dispatch TableRef [it] (print-method it *out*))

(s/def ::ref #(instance? TableRef %))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]} ; data-readers.clj
(defn ->ref
  (^xtdb.table.TableRef [schema+table]
   (cond
     (string? schema+table) (let [[table schema] (reverse (str/split schema+table #"/" 2))]
                              (->ref schema table))
     (simple-symbol? schema+table) (->ref nil schema+table)
     (qualified-symbol? schema+table) (->ref (namespace schema+table) (name schema+table))
     (keyword? schema+table) (recur (symbol (NormalForm/normalTableName schema+table)))))

  (^xtdb.table.TableRef [schema table]
   (TableRef. (or (some-> schema str) "public") (str table))))

(defn ref->sym [^TableRef table-ref]
  (symbol (.getSchemaName table-ref) (.getTableName table-ref)))

(def transit-read-handlers
  {"xt/table" (transit/read-handler (fn [{:keys [schema-name table-name]}]
                                      (->ref schema-name table-name)))})

(def transit-write-handlers
  {TableRef (transit/write-handler "xt/table"
                                   (fn [^TableRef table]
                                     {:schema-name (.getSchemaName table)
                                      :table-name (.getTableName table)}))})
