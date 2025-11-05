 (ns xtdb.bench.cloud.scripts.tasks
   (:require [babashka.process :as process]
             [cheshire.core :as json]
             [clojure.pprint :as pprint]))

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
  [n]
  (let [job (first (:items (kubectl "get" "jobs" "-n" n)))
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
  * :namespace -- Kubernetes namespace (default "cloud-benchmark")
  * :component -- app.kubernetes.io/component label to match (default "benchmark")
  * :attempts -- number of polling attempts (default 36)
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
                                      attempt attempts (count running-pods) elapsed required-running-seconds))
                     (Thread/sleep sleep-ms)
                     (recur (inc attempt) started))))

               :else
               (do
                 (when logging
                   (println (format "Attempt %d/%d: pods detected but not yet running; retrying." attempt attempts)))
                  (Thread/sleep sleep-ms)
                  (recur (inc attempt) nil)))))))))))

(comment
  (pprint/pprint (kubectl "get" "pods" "-n" "cloud-benchmark"))

  (benchmark-status "cloud-benchmark")

  (await-failure {:namespace "cloud-benchmark" :component "benchmark" :logging true})

  )