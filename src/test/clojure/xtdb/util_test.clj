(ns xtdb.util-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.util :as util])
  (:import java.nio.ByteBuffer
           java.nio.file.Paths
           (java.nio.channels FileChannel)))

(deftest uuid-conversion-utils-test
  (let [uuid (random-uuid)]
    (t/is (= uuid
             (util/byte-buffer->uuid
               (util/uuid->byte-buffer uuid))
             (util/byte-buffer->uuid
               (ByteBuffer/wrap
                 (util/uuid->bytes uuid)))))))

(defn isa-file-channel? [file-channel]
  (isa? (type file-channel) java.nio.channels.FileChannel))

(deftest file-channel-test
  (t/testing "Normal file channel read"
    (t/is (isa-file-channel? (util/->file-channel (Paths/get "src/test/resources/test-config.yaml" (into-array String []))))))
  (t/testing "File channel read with Resource temporarily unavailable exception"
    (let [first-read? (atom true)]
      (with-redefs [util/-file-channel-open
                    (fn [path open-opts]
                      (if @first-read?
                        (do (reset! first-read? false)
                            (throw (java.nio.file.FileSystemException. "Resource temporarily unavailable")))
                        (FileChannel/open path open-opts)))]
        (t/is (isa-file-channel? (util/->file-channel (Paths/get "src/test/resources/test-config.yaml" (into-array String []))))))))
  (t/testing "File channel read with other exception fails"
    (let [first-read? (atom true)]
      (with-redefs [util/-file-channel-open
                    (fn [path open-opts]
                      (if @first-read?
                        (do (reset! first-read? false)
                            (throw (java.nio.file.FileSystemException. "File not found")))
                        (FileChannel/open path open-opts)))]
        (t/is (thrown? java.nio.file.FileSystemException (util/->file-channel (Paths/get "src/test/resources/test-config.yaml" (into-array String []))))))))
  (t/testing "File channel read with short duration of causing an exception"
    (let [start (atom (System/currentTimeMillis))]
      (with-redefs [util/-file-channel-open
                    (fn [path open-opts]
                      (if (< (- (System/currentTimeMillis) @start) 150)
                        (throw (java.nio.file.FileSystemException. "Resource temporarily unavailable"))
                        (FileChannel/open path open-opts)))]
        (t/is (isa-file-channel? (util/->file-channel (Paths/get "src/test/resources/test-config.yaml" (into-array String []))))))))
  (t/testing "File channel read with long duration of causing an exception fails"
    (let [start (atom (System/currentTimeMillis))]
      (with-redefs [util/-file-channel-open
                    (fn [path open-opts]
                      (if (< (- (System/currentTimeMillis) @start) 305)
                        (throw (java.nio.file.FileSystemException. "Resource temporarily unavailable"))
                        (FileChannel/open path open-opts)))]
        (t/is (thrown? java.nio.file.FileSystemException (util/->file-channel (Paths/get "src/test/resources/test-config.yaml" (into-array String [])))))))))