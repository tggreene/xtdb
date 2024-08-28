(ns xtdb.azure-test
  (:require [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.azure :as azure]
            [xtdb.buffer-pool :as bp]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.node :as xtn]
            [xtdb.object-store-test :as os-test]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (com.azure.storage.blob BlobContainerClient)
           (com.azure.storage.blob.models BlobItem BlobListDetails ListBlobsOptions)
           (java.io Closeable)
           (java.nio ByteBuffer)
           (java.nio.file Path)
           (java.time Duration)
           (xtdb.api.storage AzureBlobStorage ObjectStore)
           (xtdb.buffer_pool RemoteBufferPool)
           (xtdb.multipart IMultipartUpload SupportsMultipart)))

(def storage-account "xtdbteststorageaccount")
(def container "xtdb-test-object-store")
(def servicebus-namespace "xtdb-test-storage-account-eventbus")
(def servicebus-topic-name "xtdb-test-storage-bus-topic")
(def config-present? (some? (and (System/getenv "AZURE_CLIENT_ID")
                                 (System/getenv "AZURE_CLIENT_SECRET")
                                 (System/getenv "AZURE_TENANT_ID")
                                 (System/getenv "AZURE_SUBSCRIPTION_ID"))))
(def wait-time-ms 10000)

(defn cli-available? []
  (= 0 (:exit (sh/sh "az" "--help"))))

(defn logged-in? []
  (= 0 (:exit (sh/sh "az" "account" "show"))))

(defn run-if-auth-available [f]
  (cond
    config-present? (f)

    (not (cli-available?))
    (log/warn "azure cli is unavailable and the auth env vars are not set: AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID")

    (not (logged-in?))
    (log/warn "azure cli appears to be available but you are not logged in, run `az login` before running the tests")

    :else (f)))

(t/use-fixtures :once run-if-auth-available)

(defn object-store ^Closeable [prefix]
  (let [factory (-> (AzureBlobStorage/azureBlobStorage storage-account container servicebus-namespace servicebus-topic-name)
                    (.prefix (util/->path (str prefix))))]
    (azure/open-object-store factory)))

(t/deftest ^:azure put-delete-test
  (with-open [os (object-store (random-uuid))]
    (os-test/test-put-delete os)))

(t/deftest ^:azure range-test
  (with-open [os (object-store (random-uuid))]
    (os-test/test-range os)))

(t/deftest ^:azure list-test
  (with-open [os (object-store (random-uuid))]
    (os-test/test-list-objects os)))

(t/deftest ^:azure list-test-with-prior-objects
  (let [prefix (random-uuid)]
    (with-open [os (object-store prefix)]
      (os-test/put-edn os (util/->path "alice") :alice)
      (os-test/put-edn os (util/->path "alan") :alan)
      (t/is (= (mapv util/->path ["alan" "alice"])
               (.listAllObjects ^ObjectStore os))))

    (with-open [os (object-store prefix)]
      (t/testing "prior objects will still be there, should be available on a list request"
        (t/is (= (mapv util/->path ["alan" "alice"])
                 (.listAllObjects ^ObjectStore os))))

      (t/testing "should be able to delete prior objects and have that reflected in list objects output"
        @(.deleteObject ^ObjectStore os (util/->path "alice"))
        (t/is (= (mapv util/->path ["alan"])
                 (.listAllObjects ^ObjectStore os)))))))

(t/deftest ^:azure multiple-object-store-list-test
  (let [prefix (random-uuid)]
    (with-open [os-1 (object-store prefix)
                os-2 (object-store prefix)]
      (os-test/put-edn os-1 (util/->path "alice") :alice)
      (os-test/put-edn os-2 (util/->path "alan") :alan)
      (Thread/sleep wait-time-ms)

      (t/is (= (mapv util/->path ["alan" "alice"])
               (.listAllObjects ^ObjectStore os-1)))

      (t/is (= (mapv util/->path ["alan" "alice"])
               (.listAllObjects ^ObjectStore os-2))))))

(t/deftest ^:azure qualified-connection-strings
  (let [prefix (random-uuid)]
    (t/testing "neither storageAccount nor storageAccountEndpoint provided - should throw illegal-arg"
      (t/is (thrown-with-msg? IllegalArgumentException
                              #"At least one of storageAccount or storageAccountEndpoint must be provided."
                              (azure/open-object-store (-> (AzureBlobStorage/azureBlobStorage nil container servicebus-namespace servicebus-topic-name)
                                                           (.prefix (util/->path (str prefix))))))))

    (t/testing "storageAccountEndpoint specified - should work correctly"
      (with-open [os (azure/open-object-store (-> (AzureBlobStorage/azureBlobStorage nil container servicebus-namespace servicebus-topic-name)
                                                  (.prefix (util/->path (str prefix)))
                                                  (.storageAccountEndpoint "https://xtdbteststorageaccount.blob.core.windows.net")))]
        (os-test/put-edn os (util/->path "alice") :alice)
        (t/is (= (mapv util/->path ["alice"]) (.listAllObjects ^ObjectStore os)))))

    (t/testing "neither serviceBusNamespace or serviceBusNamespaceFQDN provided - should throw illegal-arg"
      (t/is (thrown-with-msg? IllegalArgumentException
                              #"At least one of serviceBusNamespace or serviceBusNamespaceEndpoint must be provided."
                              (azure/open-object-store (-> (AzureBlobStorage/azureBlobStorage storage-account container nil servicebus-topic-name)
                                                           (.prefix (util/->path (str prefix))))))))

    (t/testing "serviceBusNamespaceFQDN specified - should work correctly"
      (with-open [os (azure/open-object-store (-> (AzureBlobStorage/azureBlobStorage storage-account container nil servicebus-topic-name)
                                                  (.prefix (util/->path (str prefix)))
                                                  (.serviceBusNamespaceFQDN "xtdb-test-storage-account-eventbus.servicebus.windows.net")))]
        (os-test/put-edn os (util/->path "alan") :alan)
        (t/is (= (mapv util/->path ["alan" "alice"]) (.listAllObjects ^ObjectStore os)))))))

(defn list-filenames [^BlobContainerClient blob-container-client ^Path prefix ^ListBlobsOptions list-opts]
  (->> (.listBlobs blob-container-client list-opts nil)
       (.iterator)
       (iterator-seq)
       (mapv (fn [^BlobItem blob-item]
               (.relativize prefix (util/->path (.getName blob-item)))))
       (set)))

(defn fetch-uncomitted-blobs [^BlobContainerClient blob-container-client ^Path prefix]
  (let [base-opts (-> (ListBlobsOptions.)
                      (.setPrefix (str prefix)))
        comitted-blobs (list-filenames blob-container-client prefix base-opts)
        all-blobs (list-filenames blob-container-client
                                  prefix
                                  (.setDetails base-opts
                                               (-> (BlobListDetails.)
                                                   (.setRetrieveUncommittedBlobs true))))]
    (set/difference all-blobs comitted-blobs)))

(t/deftest ^:azure multipart-start-and-cancel
  (with-open [os (object-store (random-uuid))]
    (let [blob-container-client (:blob-container-client os)
          prefix (:prefix os)]
      (t/testing "Call to start multipart should work/return an object"
        (let [multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os (util/->path "test-multi-created"))]
          (t/is multipart-upload)

          (t/testing "Uploading a part should create an uncomitted blob"
            (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
              (= #{"test-multi-created"} uncomitted-blobs)))

          (t/testing "Call to abort a multipart upload should work - uncomitted blob removed & no file present"
            @(.abort multipart-upload)

            (log/info "prefix" prefix)

            (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
              (= #{} uncomitted-blobs))

            (= #{} (list-filenames blob-container-client prefix (-> (ListBlobsOptions.)
                                                                    (.setPrefix (str prefix)))))))))))

(t/deftest ^:azure multipart-put-test
  (with-open [os (object-store (random-uuid))]
    (let [blob-container-client (:blob-container-client os)
          prefix (:prefix os)
          multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os (util/->path "test-multi-put"))
          part-size 500
          file-part-1 ^ByteBuffer (os-test/generate-random-byte-buffer part-size)
          file-part-2 ^ByteBuffer (os-test/generate-random-byte-buffer part-size)]

      ;; Uploading parts to multipart upload
      @(.uploadPart multipart-upload (.flip file-part-1))
      @(.uploadPart multipart-upload (.flip file-part-2))

      (t/testing "Call to complete a multipart upload should work - should be removed from the uncomitted list"
        @(.complete multipart-upload)
        (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
          (t/is (= #{} uncomitted-blobs))))

      (t/testing "Multipart upload works correctly - file present and contents correct"
        (t/is (= (mapv util/->path ["test-multi-put"])
                 (.listAllObjects ^ObjectStore os)))

        (let [^ByteBuffer uploaded-buffer @(.getObject ^ObjectStore os (util/->path "test-multi-put"))]
          (t/testing "capacity should be equal to total of 2 parts"
            (t/is (= (* 2 part-size) (.capacity uploaded-buffer)))))))))

(t/deftest ^:azure multipart-multi-object-store-list-test
  (let [prefix (random-uuid)]
    (with-open [os-1 (object-store prefix)
                os-2 (object-store prefix)]
      (let [multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os-1 (util/->path "multi-put-list-test"))]
      ;; Doing a multipart upload against the first object store
        @(.uploadPart multipart-upload (.flip ^ByteBuffer (os-test/generate-random-byte-buffer 500)))
        @(.uploadPart multipart-upload (.flip ^ByteBuffer (os-test/generate-random-byte-buffer 500)))
        @(.complete multipart-upload)

      ;; Wait to let the service bus catch up
        (Thread/sleep wait-time-ms)

        (t/testing "File should be available on object store that submitted it"
          (t/is (= (mapv util/->path ["multi-put-list-test"])
                   (.listAllObjects ^ObjectStore os-1))))

        (t/testing "File should be available on the other object store (ie, via service bus subscription)"
          (t/is (= (mapv util/->path ["multi-put-list-test"])
                   (.listAllObjects ^ObjectStore os-2))))

        (t/testing "Deleting file should make it unavailable on both object stores"
        ;; Delete object from store
          @(.deleteObject ^ObjectStore os-1 (util/->path "multi-put-list-test"))

        ;; Wait to let the service bus catch up
          (Thread/sleep wait-time-ms)

          (t/is (= [] (.listAllObjects ^ObjectStore os-1)))
          (t/is (= [] (.listAllObjects ^ObjectStore os-2))))))))

(t/deftest ^:azure node-level-test
  (util/with-tmp-dirs #{local-disk-cache}
    (util/with-open [node (xtn/start-node
                           {:storage [:remote
                                      {:object-store [:azure {:storage-account storage-account
                                                              :container container
                                                              :servicebus-namespace servicebus-namespace
                                                              :servicebus-topic-name servicebus-topic-name
                                                              :prefix (util/->path (str "xtdb.azure-test." (random-uuid)))}]
                                       :local-disk-cache local-disk-cache}]})]
      ;; Submit some documents to the node
      (t/is (= true
               (:committed? (xt/execute-tx node [[:put-docs :bar {:xt/id "bar1"}]
                                                 [:put-docs :bar {:xt/id "bar2"}]
                                                 [:put-docs :bar {:xt/id "bar3"}]]))))

      ;; Ensure finish-chunk! works
      (t/is (nil? (tu/finish-chunk! node)))

      ;; Ensure can query back out results
      (t/is (= [{:e "bar2"} {:e "bar1"} {:e "bar3"}]
               (xt/q node '(from :bar [{:xt/id e}]))))

      (let [{:keys [^ObjectStore object-store] :as buffer-pool} (val (first (ig/find-derived (:system node) :xtdb/buffer-pool)))]
        (t/is (instance? RemoteBufferPool buffer-pool))
        (t/is (instance? ObjectStore object-store))
        ;; Ensure some files are written
        (t/is (seq (.listAllObjects object-store)))))))

;; Using large enough TPCH ensures multiparts get properly used within the bufferpool
(t/deftest ^:azure tpch-test-node
  (util/with-tmp-dirs #{local-disk-cache}
    (util/with-open [node (xtn/start-node
                           {:storage [:remote {:object-store [:azure {:storage-account storage-account
                                                                      :container container
                                                                      :servicebus-namespace servicebus-namespace
                                                                      :servicebus-topic-name servicebus-topic-name
                                                                      :prefix (util/->path (str "xtdb.azure-test." (random-uuid)))}]
                                               :local-disk-cache local-disk-cache}]})]
                                      ;; Submit tpch docs
      (-> (tpch/submit-docs! node 0.05)
          (tu/then-await-tx node (Duration/ofHours 1)))

                                      ;; Ensure finish-chunk! works
      (t/is (nil? (tu/finish-chunk! node)))

      (let [{:keys [^ObjectStore object-store] :as buffer-pool} (val (first (ig/find-derived (:system node) :xtdb/buffer-pool)))]
        (t/is (instance? RemoteBufferPool buffer-pool))
        (t/is (instance? ObjectStore object-store))
        ;; Ensure some files are written
        (t/is (seq (.listAllObjects object-store)))))))

(t/deftest ^:azure multipart-uploads-with-more-parts-work-correctly
  (with-open [os (object-store (random-uuid))]
    (let [blob-container-client (:blob-container-client os)
          prefix (:prefix os)
          multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os (util/->path "test-larger-multi-put"))
          part-size 500]

      (dotimes [_ 20]
        (let [file-part ^ByteBuffer (os-test/generate-random-byte-buffer part-size)]
          @(.uploadPart multipart-upload (.flip file-part))))

      (t/testing "Call to complete a multipart upload should work - should be removed from the uncomitted list"
        @(.complete multipart-upload)
        (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
          (t/is (= #{} uncomitted-blobs))))

      (t/testing "Multipart upload works correctly - file present and contents correct"
        (t/is (= (mapv util/->path ["test-larger-multi-put"])
                 (.listAllObjects ^ObjectStore os)))

        (let [^ByteBuffer uploaded-buffer @(.getObject ^ObjectStore os (util/->path "test-larger-multi-put"))]
          (t/testing "capacity should be equal to total of 20 parts"
            (t/is (= (* 20 part-size) (.capacity uploaded-buffer)))))))))

(t/deftest ^:azure multipart-object-already-exists
  (with-open [os (object-store (random-uuid))]
    (let [blob-container-client (:blob-container-client os)
          prefix (:prefix os)
          part-size 500]

      (t/testing "Initial multipart works correctly"
        (let [initial-multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os (util/->path "test-multipart"))]
          (dotimes [_ 2]
            (let [file-part ^ByteBuffer (os-test/generate-random-byte-buffer part-size)]
              @(.uploadPart initial-multipart-upload (.flip file-part))))

          @(.complete initial-multipart-upload)

          (t/is (= (mapv util/->path ["test-multipart"])
                   (.listAllObjects ^ObjectStore os)))

          (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
            (t/is (= #{} uncomitted-blobs)))))

      (t/testing "Attempt to multipart upload to an existing object shouldn't throw, should abort and remove uncomitted blobs"
        (let [second-multipart-upload ^IMultipartUpload @(.startMultipart ^SupportsMultipart os (util/->path "test-multipart"))]
          (dotimes [_ 3]
            (let [file-part ^ByteBuffer (os-test/generate-random-byte-buffer part-size)]
              (t/is @(.uploadPart second-multipart-upload (.flip file-part)))))

          @(.complete second-multipart-upload)

          (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
            (t/is (= #{} uncomitted-blobs)))))

      (t/testing "still has the original object"
        (t/is (= (mapv util/->path ["test-multipart"])
                 (.listAllObjects ^ObjectStore os)))

        (let [^ByteBuffer uploaded-buffer @(.getObject ^ObjectStore os (util/->path "test-multipart"))]
          (t/testing "capacity should be equal to total of 2 parts (ie, initial upload)"
            (t/is (= (* 2 part-size) (.capacity uploaded-buffer)))))))))

(t/deftest ^:azure interrupt-multipart-upload
  (with-open [os (object-store (random-uuid))]
    (let [blob-container-client (:blob-container-client os)
          prefix (:prefix os)
          parts (repeatedly 5 #(.flip (os-test/generate-random-byte-buffer 10000000)))
          upload-thread (Thread.
                         (fn []
                           (try
                             ;; Start the multipart upload
                             (#'bp/upload-multipart-buffers os (util/->path "multipart-interrupted") parts)
                             (catch InterruptedException _
                               (log/warn "Upload was interrupted")))))]
            ;; Start the upload thread
      (.start upload-thread)

            ;; Give it some time to start uploading
      (Thread/sleep 3000)

      (.interrupt upload-thread)

      (t/testing "no uncomitted blobs should be present"
        (let [uncomitted-blobs (fetch-uncomitted-blobs blob-container-client prefix)]
          (t/is (= #{} uncomitted-blobs))))

      (t/testing "no comitted blobs should be present"
        (= #{} (list-filenames blob-container-client prefix (-> (ListBlobsOptions.)
                                                                (.setPrefix (str prefix)))))))))
