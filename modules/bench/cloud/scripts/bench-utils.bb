#!/usr/bin/env bb

(require '[clojure.string :as str]
         '[cheshire.core :as json]
         '[clojure.pprint :as pprint])

(defn parse-tpch-log
  [log-file-path]
  (let [content (slurp log-file-path)
        lines (str/split-lines content)
        stage-lines (filter #(str/starts-with? % "{\"stage\":") lines)
        stages (mapv (fn [line]
                       (try
                         (json/parse-string line true)
                         (catch Exception e
                           (throw (ex-info (str "Failed to parse JSON line: " line)
                                           {:line line :error (.getMessage e)})))))
                     stage-lines)
        query-stages (filterv (fn [stage]
                                (let [stage-name (:stage stage)]
                                  (or (str/starts-with? stage-name "hot-queries-q")
                                      (str/starts-with? stage-name "cold-queries-q"))))
                              stages)
        benchmark-line (first (filter #(str/includes? % "\"benchmark\":") lines))
        benchmark-summary (when benchmark-line
                            (try
                              (json/parse-string benchmark-line true)
                              (catch Exception _ nil)))
        benchmark-total-time-ms (when benchmark-summary
                                  (:time-taken-ms benchmark-summary))]
    {:all-stages stages
     :query-stages query-stages
     :ingest-stages (filterv #(contains? #{"submit-docs" "sync" "finish-block" "compact" "ingest"} (:stage %)) stages)
     :benchmark-total-time-ms benchmark-total-time-ms
     :benchmark-summary benchmark-summary}))

(defn- title-case
  "Simple title-case conversion for hyphenated strings."
  [s]
  (->> (str/split s #"-")
       (map str/capitalize)
       (str/join " ")))

(defn- stage->query-row
  "Convert a stage entry into a printable table row."
  [idx {:keys [stage time-taken-ms]}]
  (when-let [[_ hot-cold query-num name] (re-find #"^((?:hot|cold))-queries-q(\d+)-(.*)$" stage)]
    (let [friendly-name (title-case name)
          query-index (Long/parseLong query-num)
          duration-pt (.toString (java.time.Duration/ofMillis time-taken-ms))]
      {:query-order idx
       :query-index query-index
       :temp (str/capitalize hot-cold)
       :q (str "Q" query-num)
       :query-name friendly-name
       :stage stage
       :time-taken-ms time-taken-ms
       :time-taken-duration duration-pt})))

(defn query-rows
  "Return the query rows and total time for a parsed summary."
  [summary]
  (let [rows (->> (:query-stages summary)
                  (map-indexed stage->query-row)
                  (remove nil?)
                  (sort-by :query-order)
                  vec)
        total-ms (reduce + (map :time-taken-ms rows))
        rows-with-percent (mapv (fn [row]
                                  (let [ms (:time-taken-ms row)
                                        pct (if (pos? total-ms)
                                              (* 100.0 (/ ms total-ms))
                                              0.0)]
                                    (-> row
                                        (assoc :percent-of-total (format "%.2f%%" pct))
                                        (dissoc :query-order :query-index))))
                                rows)]
    {:rows rows-with-percent
     :total-ms total-ms}))

(defn print-query-table
  "Print the query rows in a table format, followed by the total."
  [summary]
  (let [{:keys [rows total-ms]} (query-rows summary)]
    (if (seq rows)
      (do
        (pprint/print-table [:temp :q :query-name :time-taken-ms :time-taken-duration :percent-of-total] rows)
        (println)
        (println (format "Total query time: %d ms (%s)" total-ms (.toString (java.time.Duration/ofMillis total-ms))))
        (when-let [benchmark (:benchmark-total-time-ms summary)]
          (println (format "Benchmark total time: %d ms (%s)" benchmark (.toString (java.time.Duration/ofMillis benchmark))))))
      (println "No query stages found."))))

(defn slack-query-table
  "Return the query table as a Slack-friendly code block."
  [summary]
  (str "```\n" (with-out-str (print-query-table summary)) "```"))

(defn github-query-table
  "Render the query table as GitHub Actions summary Markdown."
  [summary]
  (let [{:keys [rows total-ms]} (query-rows summary)
        benchmark (:benchmark-total-time-ms summary)]
    (if (seq rows)
      (let [header "| Temp | Query | Query Name | Time (ms) | Duration | % of total |"
            separator "|------|-------|------------|-----------|----------|------------|"
            body (->> rows
                      (map (fn [{:keys [temp q query-name time-taken-ms time-taken-duration percent-of-total]}]
                             (format "| %s | %s | %s | %d | %s | %s |"
                                     temp q query-name time-taken-ms time-taken-duration percent-of-total)))
                      (str/join "\n"))
            total-line (format "Total query time: %d ms (%s)"
                               total-ms
                               (.toString (java.time.Duration/ofMillis total-ms)))
            benchmark-line (when benchmark
                             (format "Benchmark total time: %d ms (%s)"
                                     benchmark
                                     (.toString (java.time.Duration/ofMillis benchmark))))]
        (str header "\n"
             separator "\n"
             body "\n\n"
             total-line
             (when benchmark-line
               (str "\n" benchmark-line))))
      "No query stages found.")))

(defn append-github-summary!
  "Append the query table to the GitHub Actions step summary, if available."
  [summary]
  (if-let [summary-path (System/getenv "GITHUB_STEP_SUMMARY")]
    (spit summary-path
          (str "\n" (github-query-table summary) "\n")
          :append true)
    (println "GITHUB_STEP_SUMMARY not set; skipping summary append.")))

;; CLI interface
(defn print-usage []
  (println "Usage: bench-utils.bb <command> [args...]")
  (println)
  (println "Commands:")
  (println "  parse-tpch-log <log-file>          - Parse TPC-H log and extract query stages"))

(defn -main [& args]
  (if (empty? args)
    (print-usage)
    (let [command (first args)
          rest-args (rest args)]
      (try
        (case command
          "parse-tpch-log"
          (if (empty? rest-args)
            (println "Error: log file path required")
            (let [log-file (first rest-args)
                  parsed (parse-tpch-log log-file)]
              (println (github-query-table parsed))
              #_
                (print-query-table parsed)))

          (do
            (println (str "Unknown command: " command))
            (print-usage)))
        (catch Exception e
          (println "Error:" e)
          (println e)
          (when-let [data (ex-data e)]
            (println "Details:" data))
          (System/exit 1))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
