(ns xtdb.memory-test
  (:require [clojure.test :as t]
            [integrant.core :as ig]
            [xtdb.memory]))

(t/deftest test-memory-trimmer-disabled
  (let [expanded (ig/expand {:xtdb/memory-trimmer (xtdb.api.MemoryTrimmerConfig. false (java.time.Duration/ofSeconds 10))})
        result (ig/init-key :xtdb/memory-trimmer (get expanded :xtdb/memory-trimmer))]
    (t/is (nil? result) "Disabled trimmer should return nil")))

(t/deftest test-memory-trimmer-enabled-lifecycle
  (let [expanded (ig/expand {:xtdb/memory-trimmer (xtdb.api.MemoryTrimmerConfig. true (java.time.Duration/ofSeconds 60))})
        stop-fn (ig/init-key :xtdb/memory-trimmer (get expanded :xtdb/memory-trimmer))]
    ;; On macOS, malloc_trim isn't available so stop-fn will be nil.
    ;; On Linux (glibc), stop-fn will be a function.
    ;; Either way, halt should be safe.
    (ig/halt-key! :xtdb/memory-trimmer stop-fn)
    (t/is true "Lifecycle completed without error")))
