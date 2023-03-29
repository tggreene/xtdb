(ns xtdb.align-test
  (:require [clojure.test :as t]
            [xtdb.align :as align]
            [xtdb.datalog :as xt]
            [xtdb.expression :as expr]
            [xtdb.ingester :as ingest]
            [xtdb.node :as node]
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [xtdb.vector.indirect :as iv]
            [xtdb.vector.writer :as vw])
  (:import java.util.List
           org.apache.arrow.vector.VectorSchemaRoot))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-align
  (with-open [age-vec (tu/open-vec "age" [12 42 15 83 25])
              age-row-id-vec (tu/open-vec "_row-id" [2 5 9 12 13])

              age-root (let [^List vecs [age-row-id-vec age-vec]]
                         (VectorSchemaRoot. vecs))

              name-vec (tu/open-vec "name" ["Al" "Dave" "Bob" "Steve"])
              name-row-id-vec (tu/open-vec "_row-id" [1 2 9 13])

              name-root (let [^List vecs [name-row-id-vec name-vec]]
                          (VectorSchemaRoot. vecs))]

    (let [roots [(iv/<-root name-root) (iv/<-root age-root)]

          row-ids (doto (align/->row-id-bitmap (.select (expr/->expression-relation-selector '(<= age 30) {:col-types {'age :i64}})
                                                        tu/*allocator*
                                                        (iv/->indirect-rel [(iv/->direct-vec age-vec)])
                                                        vw/empty-params)
                                               (iv/->direct-vec age-row-id-vec))
                    (.and (align/->row-id-bitmap (.select (expr/->expression-relation-selector '(<= name "Frank") {:col-types {'name :utf8}})
                                                          tu/*allocator*
                                                          (iv/->indirect-rel [(iv/->direct-vec name-vec)])
                                                          vw/empty-params)
                                                 (iv/->direct-vec name-row-id-vec))))]

      (with-open [row-id-col (tu/open-vec "_row-id" (vec (.toArray row-ids)))]
        (let [temporal-rel (iv/->indirect-rel [(iv/->direct-vec row-id-col)])]
          (t/is (= [{:name "Dave", :age 12, :_row-id 2}
                    {:name "Bob", :age 15, :_row-id 9}]
                   (iv/rel->rows (align/align-vectors roots temporal-rel)))))))))

(t/deftest test-aligns-temporal-columns-correctly-363
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put 'foo {:id :my-doc, :last_updated "tx1"}]] {:sys-time #inst "3000"})

    (xt/submit-tx node [[:put 'foo {:id :my-doc, :last_updated "tx2"}]] {:sys-time #inst "3001"})

    (t/is (= [{:system_time_start (util/->zdt #inst "3000")
               :system_time_end (util/->zdt #inst "3001")
               :last_updated "tx1"}
              {:system_time_start (util/->zdt #inst "3001")
               :system_time_end (util/->zdt util/end-of-time)
               :last_updated "tx1"}
              {:system_time_start (util/->zdt #inst "3001")
               :system_time_end (util/->zdt util/end-of-time)
               :last_updated "tx2"}]
             (tu/query-ra '[:scan {:table foo, :for-sys-time :all-time}
                            [{system_time_start (< system_time_start #time/zoned-date-time "3002-01-01T00:00Z")}
                             {system_time_end (> system_time_end #time/zoned-date-time "2999-01-01T00:00Z")}
                             last_updated]]
                          {:node node})))))