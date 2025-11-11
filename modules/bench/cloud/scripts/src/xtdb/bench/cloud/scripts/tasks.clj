 (ns xtdb.bench.cloud.scripts.tasks
   (:require [babashka.process :as process]
             [cheshire.core :as json]
             [clojure.pprint :as pprint]
             [clojure.string :as str]))

(defn kubectl
  [& args]
  (try
    (-> (apply process/shell {:continue true :out :string :err :string} "kubectl" "-o" "json" args)
        :out
        (json/parse-string true))
    (catch Exception e
      (println "Error running kubectl:" (.getMessage e))
      (println e))))

(defn benchmark-status
  [namespace]
  (let [job (first (:items (kubectl "get" "jobs" "-n" namespace)))
        conditions (->> (get-in job [:status :conditions] [])
                        (keep (fn [{:keys [type status]}]
                                (when (= "True" status) type)))
                        set)]
    (cond
      (empty? job) :no-deployment-found
      (pos? (get-in job [:status :active] 0)) :in-progress
      (or (pos? (get-in job [:status :failed] 0))
          (contains? conditions "Failed")) :failed
      (or (pos? (get-in job [:status :succeeded] 0))
          (contains? conditions "Complete")) :complete
      :else :unknown)))

(def failure-waiting-reasons
  #{"CrashLoopBackOff" "ImagePullBackOff" "ErrImagePull"
    "CreateContainerConfigError" "RunContainerError" "ContainerCannotRun"
    "Error"})

(defn- container-terminated-with-error?
  [container-status]
  (when-let [terminated (get-in container-status [:state :terminated])]
    (not (zero? (get terminated :exitCode 0)))))

(defn- container-waiting-with-error?
  [container-status]
  (when-let [waiting (get-in container-status [:state :waiting])]
    (when-let [reason (get waiting :reason)]
      (boolean (contains? failure-waiting-reasons reason)))))

(defn- container-failure?
  [container-status]
  (or (container-terminated-with-error? container-status)
      (container-waiting-with-error? container-status)))

(defn- pod-failing?
  [pod]
  (let [status (:status pod)]
    (or (= "Failed" (:phase status))
        (some container-failure? (:containerStatuses status))
        (some container-failure? (:initContainerStatuses status)))))

(defn- init-container-succeeded?
  [container-status]
  (let [terminated (get-in container-status [:state :terminated])]
    (and terminated (zero? (get terminated :exitCode 0)))))

(defn- pod-running?
  [pod]
  (let [status (:status pod)]
    (and (= "Running" (:phase status))
         (some #(get-in % [:state :running]) (:containerStatuses status))
         (every? init-container-succeeded? (:initContainerStatuses status)))))

(defn- pod-succeeded?
  [pod]
  (= "Succeeded" (get-in pod [:status :phase])))

(defn- now-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn await-failure
  "Poll benchmark pods for early failures, returning a status map summarising the outcome.

  Returns one of:
  * {:status :failed, :pods [...]}
  * {:status :completed, :pods [...]}
  * {:status :stable, :pods [...], :running-seconds N}
  * {:status :timeout}

  Options:
  * :namespace -- Kubernetes namespace (default cloud-benchmark)
  * :component -- app.kubernetes.io/component label to match (default benchmark)
  * :attempts -- number of polling attempts (default 30)
  * :sleep-ms -- delay between attempts in milliseconds (default 10000)
  * :required-running-seconds -- time pods must remain running before returning :stable (default 120)
  "
  ([] (await-failure {}))
  ([{:keys [namespace component attempts sleep-ms required-running-seconds logging]
     :or {namespace "cloud-benchmark"
          component "benchmark"
          attempts 30
          sleep-ms 10000
          required-running-seconds 120
          logging true}}]
   (when logging
     (println (format "Checking for immediate %s pod failures in namespace %s..." component namespace)))
   (loop [attempt 1
          running-since nil]
     (if (> attempt attempts)
       {:status :timeout
        :attempts attempts}
       (let [response (kubectl "get" "pods" "-n" namespace)
             pods (when response
                    (->> (:items response)
                         (filter #(= component (get-in % [:metadata :labels :app.kubernetes.io/component])))))
             pod-count (count pods)]
         (cond
           (nil? response)
           (do
             (when logging
               (println (format "Attempt %d/%d: kubectl returned no data; retrying." attempt attempts)))
             (Thread/sleep sleep-ms)
             (recur (inc attempt) nil))

           (zero? pod-count)
           (do
             (when logging
               (println (format "Attempt %d/%d: no %s pods detected yet." attempt attempts component)))
             (Thread/sleep sleep-ms)
             (recur (inc attempt) nil))

           :else
           (let [failing-pods (->> pods
                                   (filter pod-failing?)
                                   (map #(get-in % [:metadata :name])))
                 completed-pods (->> pods
                                     (filter pod-succeeded?)
                                     (map #(get-in % [:metadata :name])))
                 running-pods (->> pods
                                   (filter pod-running?)
                                   (map #(get-in % [:metadata :name])))]
             (cond
               (seq failing-pods)
               {:status :failed
                :attempt attempt
                :pods failing-pods}

               (seq completed-pods)
               {:status :completed
                :attempt attempt
                :pods completed-pods}

               (seq running-pods)
               (let [now-ts (now-seconds)
                     started (or running-since now-ts)
                     elapsed (- now-ts started)]
                 (if (>= elapsed required-running-seconds)
                   {:status :stable
                    :attempt attempt
                    :pods running-pods
                    :running-seconds elapsed}
                   (do
                     (when logging
                       (println (format "Attempt %d/%d: %d pod(s) running for %ds (target: %ds)."
                                        attempt attempts (count running-pods) elapsed required-running-seconds)))
                     (Thread/sleep sleep-ms)
                     (recur (inc attempt) started))))

               :else
               (do
                 (when logging
                   (println (format "Attempt %d/%d: %d pod(s) detected but not yet running; retrying." attempt attempts (count running-pods))))
                 (Thread/sleep sleep-ms)
                 (recur (inc attempt) nil))))))))))

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

(defn summarize-log
  [args]
  (let [[benchmark-type log-file-path] args]
    (case benchmark-type
      "tpch" (let [summary (parse-tpch-log log-file-path)]
               (print-query-table summary))
      (throw (ex-info (format "Unsupported benchmark type: %s" benchmark-type) {:benchmark-type benchmark-type})))))

(defn append-github-summary!
  "Append the query table to the GitHub Actions step summary, if available."
  [summary]
  (if-let [summary-path (System/getenv "GITHUB_STEP_SUMMARY")]
    (spit summary-path
          (str "\n" (github-query-table summary) "\n")
          :append true)
    (println "GITHUB_STEP_SUMMARY not set; skipping summary append.")))

(defn help []
  (println "Usage: bb <command> [args...]")
  (println)
  (println "Commands:")
  (println "  summarize-log <benchmark-type> <log-file> - Print a summary of a benchmark log (e.g. tpch, readings, auctionmark)")
  (println "  help                                      - Show this help message"))

(comment
  (pprint/pprint (kubectl "get" "pods" "-n" "cloud-benchmark"))

  (benchmark-status "cloud-benchmark")

  (await-failure {:namespace "cloud-benchmark" :component "benchmark" :logging true})

  )