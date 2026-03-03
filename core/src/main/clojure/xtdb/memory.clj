(ns xtdb.memory
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [xtdb.node :as xtn])
  (:import [java.lang ProcessHandle]
           [xtdb.api Xtdb$Config MemoryTrimmerConfig]))

(defn- jemalloc-loaded?
  "Checks /proc/<pid>/maps (Linux) for evidence that jemalloc is loaded into the process."
  []
  (try
    (let [pid (.pid (ProcessHandle/current))
          maps-path (java.nio.file.Path/of (str "/proc/" pid "/maps") (into-array String []))]
      (when (java.nio.file.Files/exists maps-path (into-array java.nio.file.LinkOption []))
        (str/includes? (java.nio.file.Files/readString maps-path) "jemalloc")))
    (catch Throwable t
      (log/warnf t "jemalloc-loaded? check failed")
      nil)))

(defn- ->malloc-trim-fn
  "Returns a zero-arg fn that calls glibc malloc_trim(0) via FFM, or nil if unavailable."
  []
  (try
    (let [^java.lang.foreign.Linker linker (java.lang.foreign.Linker/nativeLinker)
          ^java.lang.foreign.SymbolLookup lookup (.defaultLookup linker)
          addr-opt (.find lookup "malloc_trim")]
      (when (.isPresent addr-opt)
        (let [^java.lang.foreign.MemorySegment addr (.get addr-opt)
              ^java.lang.foreign.FunctionDescriptor desc
              (java.lang.foreign.FunctionDescriptor/of
                java.lang.foreign.ValueLayout/JAVA_INT
                (into-array java.lang.foreign.MemoryLayout
                            [java.lang.foreign.ValueLayout/JAVA_INT]))
              ^java.lang.invoke.MethodHandle handle
              (.downcallHandle linker addr desc
                              (into-array java.lang.foreign.Linker$Option []))]
          (fn malloc-trim! []
            (try
              (let [result (.invokeWithArguments handle [(int 0)])]
                (pos? (int result)))
              (catch Throwable t
                (log/debugf t "malloc_trim call failed")
                nil))))))
    (catch Throwable t
      (log/debugf t "malloc_trim not available (expected on non-glibc platforms)")
      nil)))

(defmethod xtn/apply-config! :xtdb/memory-trimmer [^Xtdb$Config config _ {:keys [enabled? interval]}]
  (.memoryTrimmer config
                  (cond-> (MemoryTrimmerConfig.)
                    (some? enabled?) (.enabled enabled?)
                    interval (.interval interval))))

(defmethod ig/expand-key :xtdb/memory-trimmer [k ^MemoryTrimmerConfig config]
  {k {:enabled? (.getEnabled config)
      :interval (.getInterval config)}})

(defmethod ig/init-key :xtdb/memory-trimmer [_ {:keys [enabled? ^java.time.Duration interval]}]
  (log/infof "memory-trimmer: init (enabled=%s, interval=%s)" enabled? interval)
  (when enabled?
    (let [jemalloc? (jemalloc-loaded?)]
      (log/infof "memory-trimmer: jemalloc-loaded? => %s" (boolean jemalloc?))
      (when jemalloc?
        (log/info "memory-trimmer: jemalloc loaded, memory reclamation handled by jemalloc"))
      (if-let [trim-fn (when-not jemalloc?
                          (->malloc-trim-fn))]
        (let [running (atom true)
              interval-ms (.toMillis interval)
              t (Thread.
                 (fn []
                   (log/info "memory-trimmer: started, calling malloc_trim(0) every" interval)
                   (while @running
                     (try
                       (Thread/sleep interval-ms)
                       (when @running
                         (let [released? (trim-fn)]
                           (log/debugf "malloc_trim(0): %s" (if released? "released pages" "nothing to release"))))
                       (catch InterruptedException _)
                       (catch Throwable t
                         (log/warnf t "memory-trimmer: unexpected error"))))))]
          (.setDaemon t true)
          (.setName t "xtdb-memory-trimmer")
          (.start t)
          (fn stop-memory-trimmer []
            (reset! running false)
            (.interrupt t)))
        (when-not jemalloc?
          (log/info "memory-trimmer: malloc_trim not available, no-op")
          nil)))))

(defmethod ig/halt-key! :xtdb/memory-trimmer [_ stop-fn]
  (when stop-fn
    (stop-fn)))
