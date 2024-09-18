(ns xtdb.cli-test
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.cli :as cli]
            [xtdb.node :as xtn])
  (:import (xtdb.indexer.live_index LiveIndex)))

(def xtdb-cli-edn
  (io/resource "xtdb/cli-test.edn"))

(def xtdb-cli-edn-env
  (io/resource "xtdb/cli-test-env.edn"))

(def xtdb-cli-yaml
  (io/resource "xtdb/cli-test.yaml"))

(defn with-file-override [files f]
  (with-redefs [io/file (some-fn files io/file)]
    (f)))

(defn with-resource-override [resources f]
  (let [og io/resource]
    (with-redefs [io/resource (fn [& args]
                                (apply (some-fn resources og) args))]
      (f))))

(defmethod ig/init-key ::foo [_ opts] opts)

(t/deftest no-config
  (t/testing "if no config present via file, returns an empty map as the node-opts"
    (t/is (= {::cli/node-opts {}} (cli/parse-args [])))))

(t/deftest test-config
  (letfn [(->system [cli-args]
            (-> (::cli/node-opts (cli/parse-args cli-args))
                ig/prep
                ig/init
                (->> (into {}))))]
    (t/testing "uses CLI supplied EDN file"
      (t/is (= {::foo {:bar {}}}
               (->system ["-f" (str (io/as-file xtdb-cli-edn))]))))

    (t/testing "uses xtdb.edn if present"
      (with-file-override {"xtdb.edn" (io/as-file xtdb-cli-edn)}
        (fn []
          (t/is (= {::foo {:bar {}}}
                   (->system []))))))))

(t/deftest test-env-loading
  (with-redefs [cli/read-env-var (fn [env-name]
                                   (when (= (str env-name) "TEST_ENV") "hello world"))]
    (letfn [(->system [cli-args]
              (-> (::cli/node-opts (cli/parse-args cli-args))
                  ig/prep
                  ig/init
                  (->> (into {}))))]

      (t/testing "EDN config - #env reader tag fetches from env"
        (t/is (= {::foo "hello world"}
                 (->system ["-f" (str (io/as-file xtdb-cli-edn-env))])))))))

(defmethod ig/init-key ::bar [_ opts] opts)

;; Expect YAML config file to return the file in `node-opts` - this will get passed to
;; start-node and subsequently decoded by the Kotlin API
(t/deftest test-config-yaml
  (letfn [(->node-opts [cli-args] (::cli/node-opts (cli/parse-args cli-args)))]

    (t/testing "Explicitly provided YAML file will output specified file-location"
      (t/is (= (io/file xtdb-cli-yaml)
               (->node-opts ["-f" (str (io/as-file xtdb-cli-yaml))])))) 

    (t/testing "returns file location of xtdb.yaml if present"
      (with-file-override {"xtdb.yaml" (io/as-file xtdb-cli-yaml)}
        (fn []
          (t/is (= (io/file (io/resource "xtdb/cli-test.yaml"))
                   (->node-opts []))))))
    
    (t/testing "also returns file location of xtdb.yaml if on the classpath"
      (with-resource-override {"xtdb.yaml" (io/as-file xtdb-cli-yaml)}
        (fn []
          (t/is (= (io/file (io/resource "xtdb/cli-test.yaml"))
                   (->node-opts []))))))
    
    (t/testing "node opts passed to start-node passes through yaml file and starts node"
      (with-open [node (xtn/start-node (->node-opts ["-f" (str (io/as-file xtdb-cli-yaml))]))] 
        (let [index ^LiveIndex (get-in node [:system :xtdb.indexer/live-index])]
          (t/is (= 65 (.log-limit index))
                "using provided config"))
        (xt/submit-tx node [[:put-docs :docs {:xt/id :foo}]])
        (t/is (= [{:e :foo}] (xt/q node '(from :docs [{:xt/id e}]))))))))
