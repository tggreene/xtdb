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
      (t/is (some #(= "foo" (:table %)) results))))

  (t/testing "full Grafana table discovery query (exact SQL from postgresMetaQuery.ts)"
    (xt/execute-tx tu/*node* [[:sql "INSERT INTO bar (_id) VALUES (1)"]])
    (let [results (xt/q tu/*node*
                    "SELECT
                       CASE WHEN quote_ident(table_schema) IN (
                         SELECT CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END
                         FROM generate_series(
                           array_lower(string_to_array(current_setting('search_path'),','),1),
                           array_upper(string_to_array(current_setting('search_path'),','),1)
                         ) AS xs(i),
                         string_to_array(current_setting('search_path'),',') AS t(s)
                       )
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
      (t/is (some #(= "bar" (:table %)) results)))))

;; --- Component SQL feature tests ---
;; Each test isolates a single SQL feature that the full Grafana queries need.
;; See: github.com/grafana/grafana postgresMetaQuery.ts, macros.go

(t/deftest array-subscript-on-column-test
  (t/testing "array subscript works on column references"
    (t/is (= [{:val "a"}]
             (xt/q tu/*node* "SELECT s[1] AS val FROM (VALUES (string_to_array('a,b,c', ','))) AS t(s)"))))

  (t/testing "array subscript with variable index from generate_series"
    (t/is (= [{:i 1, :val "a"} {:i 2, :val "b"} {:i 3, :val "c"}]
             (xt/q tu/*node* "SELECT i, s[i] AS val FROM generate_series(1, 3) AS xs(i), (VALUES (string_to_array('a,b,c', ','))) AS t(s)")))))

(t/deftest array-subscript-on-expression-test
  (t/testing "array subscript on inline function result — (string_to_array(...))[1]"
    (t/is (= [{:val "a"}]
             (xt/q tu/*node* "SELECT (string_to_array('a,b,c', ','))[1] AS val"))))

  (t/testing "array subscript on parse_ident result — (parse_ident('schema.table'))[1]"
    (t/is (= [{:schema "myschema"}]
             (xt/q tu/*node* "SELECT (parse_ident('myschema.mytable'))[1] AS schema"))))

  (t/testing "array subscript on parse_ident — second element"
    (t/is (= [{:tbl "mytable"}]
             (xt/q tu/*node* "SELECT (parse_ident('myschema.mytable'))[2] AS tbl")))))

(t/deftest generate-series-in-from-clause-test
  (t/testing "generate_series as table-valued function in FROM"
    (t/is (= [{:i 1} {:i 2} {:i 3}]
             (xt/q tu/*node* "SELECT i FROM generate_series(1, 3) AS xs(i)"))))

  (t/testing "generate_series with computed bounds from array functions"
    (t/is (= [{:i 1}]
             (xt/q tu/*node* "SELECT i FROM generate_series(array_lower(string_to_array('public', ','), 1), array_upper(string_to_array('public', ','), 1)) AS xs(i)")))))

(t/deftest single-arg-trim-test
  (t/testing "TRIM with single argument (no FROM keyword)"
    (t/is (= [{:val "hello"}]
             (xt/q tu/*node* "SELECT trim(' hello ') AS val"))))

  (t/testing "TRIM on array element via column reference"
    (t/is (= [{:val "public"}]
             (xt/q tu/*node* "SELECT trim(s[1]) AS val FROM (VALUES (string_to_array(' public ', ','))) AS t(s)")))))

(t/deftest function-as-from-source-test
  (t/testing "string_to_array as FROM-clause table source produces single row with array"
    (t/is (= [{:val ["a" "b" "c"]}]
             (xt/q tu/*node* "SELECT s AS val FROM string_to_array('a,b,c', ',') AS t(s)"))))

  (t/testing "cross join generate_series with string_to_array in FROM"
    (t/is (= [{:i 1, :val "a"} {:i 2, :val "b"} {:i 3, :val "c"}]
             (xt/q tu/*node* "SELECT i, s[i] AS val FROM generate_series(1, 3) AS xs(i), string_to_array('a,b,c', ',') AS t(s)")))))

(t/deftest iso-timestamp-implicit-coercion-test
  (t/testing "bare ISO 8601 string compared to timestamp column"
    (xt/execute-tx tu/*node* [[:sql "INSERT INTO readings (_id, recorded_at) VALUES (1, TIMESTAMP '2026-03-04 10:00:00+00:00')"]])
    (t/is (= 1 (count (xt/q tu/*node* "SELECT * FROM readings WHERE recorded_at >= '2026-03-04T09:00:00Z'")))))

  (t/testing "BETWEEN with bare ISO strings"
    (t/is (= 1 (count (xt/q tu/*node* "SELECT * FROM readings WHERE recorded_at BETWEEN '2026-03-04T09:00:00Z' AND '2026-03-04T11:00:00Z'"))))))

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
