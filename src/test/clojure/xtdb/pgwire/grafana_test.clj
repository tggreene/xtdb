(ns xtdb.pgwire.grafana-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-node)

(t/deftest comment-only-query-test
  (t/testing "SQL comment-only queries don't throw (pgx driver sends '-- ping')"
    (t/is (some? (xt/q tu/*node* "-- ping"))))

  (t/testing "double-dash inside string literals is not treated as a comment"
    (t/is (= [{:bar "--foo"}]
             (xt/q tu/*node* "SELECT '--foo' AS bar")))))

(t/deftest version-detection-test
  (t/testing "Grafana version detection query returns PG 16 compatible version"
    (t/is (= [{:version 1600}]
             (xt/q tu/*node* "SELECT current_setting('server_version_num')::int/100 AS version")))))

(t/deftest grafana-table-discovery-test
  (t/testing "simplified table discovery query returns user tables"
    (xt/execute-tx tu/*node* [[:sql "INSERT INTO foo (_id) VALUES (1)"]])
    (let [results (xt/q tu/*node*
                    "SELECT
                       CASE WHEN quote_ident(table_schema) IN ('public')
                       THEN quote_ident(table_name)
                       ELSE quote_ident(table_schema) || '.' || quote_ident(table_name)
                       END AS \"table\"
                     FROM information_schema.tables
                     WHERE quote_ident(table_schema) NOT IN (
                       'information_schema', 'pg_catalog',
                       '_timescaledb_cache', '_timescaledb_catalog',
                       '_timescaledb_internal', '_timescaledb_config',
                       'timescaledb_information', 'timescaledb_experimental'
                     )
                     ORDER BY 1")]
      (t/is (some #(= "foo" (:table %)) results)))))

(t/deftest grafana-as-time-alias-test
  (t/testing "Grafana generates unquoted `AS time` which needs TIME to be handled as alias"
    (xt/execute-tx tu/*node* [[:sql "INSERT INTO readings (_id, recorded_at, value) VALUES (1, TIMESTAMP '2026-03-04 10:00:00+00:00', 42.0)"]])
    ;; This is what Grafana actually sends — bare `AS time`, not `AS "time"`
    (let [results (xt/q tu/*node* "SELECT recorded_at AS time, value FROM readings ORDER BY time")]
      (t/is (= 1 (count results)))
      (t/is (some? (:time (first results))))
      (t/is (= 42.0 (:value (first results)))))))

(t/deftest grafana-time-series-query-test
  (t/testing "end-to-end time-series query with explicit TIMESTAMP literals and quoted alias"
    (xt/execute-tx tu/*node* [[:sql "INSERT INTO readings (_id, recorded_at, value) VALUES (1, TIMESTAMP '2026-03-04 10:00:00+00:00', 42.0)"]])
    (let [results (xt/q tu/*node*
                    "SELECT recorded_at AS \"time\", value FROM readings WHERE recorded_at BETWEEN TIMESTAMP '2026-03-04 09:00:00+00:00' AND TIMESTAMP '2026-03-04 11:00:00+00:00' ORDER BY recorded_at")]
      (t/is (= 1 (count results)))
      (t/is (= 42.0 (:value (first results)))))))
