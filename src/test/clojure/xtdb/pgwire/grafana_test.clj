(ns xtdb.pgwire.grafana-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-node)

(t/deftest comment-only-query-test
  (t/testing "SQL comment-only queries don't throw (pgx driver sends '-- ping')"
    (t/is (some? (xt/q tu/*node* "-- ping")))))

(t/deftest version-detection-test
  (t/testing "Grafana version detection query returns PG 16 compatible version"
    (t/is (= [{:version 1600}]
             (xt/q tu/*node* "SELECT current_setting('server_version_num')::int/100 AS version")))))

(t/deftest current-setting-search-path-test
  (t/testing "current_setting('search_path') returns expected value"
    (t/is (= [{:sp "public"}]
             (xt/q tu/*node* "SELECT current_setting('search_path') AS sp")))))

(t/deftest quote-ident-test
  (t/testing "quote_ident returns its input"
    (t/is (= [{:x "foo"}]
             (xt/q tu/*node* "SELECT quote_ident('foo') AS x")))))

(t/deftest string-to-array-test
  (t/testing "string_to_array splits a string by delimiter"
    (t/is (= [{:x ["a" " b" " c"]}]
             (xt/q tu/*node* "SELECT string_to_array('a, b, c', ',') AS x")))))

(t/deftest array-lower-test
  (t/testing "array_lower returns 1 for dimension 1"
    (t/is (= [{:x 1}]
             (xt/q tu/*node* "SELECT array_lower(string_to_array('a,b', ','), 1) AS x")))))

(t/deftest array-length-test
  (t/testing "array_length returns the length of a list"
    (t/is (= [{:x 2}]
             (xt/q tu/*node* "SELECT array_length(string_to_array('a,b', ','), 1) AS x")))))

(t/deftest parse-ident-test
  (t/testing "parse_ident splits a qualified identifier"
    (t/is (= [{:x ["public" "foo"]}]
             (xt/q tu/*node* "SELECT parse_ident('public.foo') AS x")))))

(t/deftest generate-series-2-arg-test
  (t/testing "generate_series with 2 args defaults step to 1"
    (t/is (= [{:x 1} {:x 2} {:x 3}]
             (xt/q tu/*node* "SELECT x FROM generate_series(1, 3) AS s(x)")))))

(t/deftest current-user-test
  (t/testing "CURRENT_USER returns current user"
    (t/is (= [{:u "xtdb"}]
             (xt/q tu/*node* "SELECT CURRENT_USER AS u")))))

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

(t/deftest grafana-query-rewrite-test
  (t/testing "table discovery query is rewritten"
    (let [grafana-query "SELECT\n    CASE WHEN \n          quote_ident(table_schema) IN (\n          SELECT\n            CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END\n          FROM\n            generate_series(\n              array_lower(string_to_array(current_setting('search_path'),','),1),\n              array_upper(string_to_array(current_setting('search_path'),','),1)\n            ) as i,\n            string_to_array(current_setting('search_path'),',') s\n          )\n      THEN quote_ident(table_name)\n      ELSE quote_ident(table_schema) || '.' || quote_ident(table_name)\n    END AS \"table\"\n    FROM information_schema.tables\n    WHERE quote_ident(table_schema) NOT IN ('information_schema',\n                             'pg_catalog',\n                             '_timescaledb_cache',\n                             '_timescaledb_catalog',\n                             '_timescaledb_internal',\n                             '_timescaledb_config',\n                             'timescaledb_information',\n                             'timescaledb_experimental')\n    ORDER BY CASE WHEN \n          quote_ident(table_schema) IN (\n          SELECT\n            CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END\n          FROM\n            generate_series(\n              array_lower(string_to_array(current_setting('search_path'),','),1),\n              array_upper(string_to_array(current_setting('search_path'),','),1)\n            ) as i,\n            string_to_array(current_setting('search_path'),',') s\n          ) THEN 0 ELSE 1 END, 1"
          rewritten (#'xtdb.pgwire/apply-grafana-rewrites grafana-query)]
      (t/is (not= grafana-query rewritten) "query should be rewritten")
      (t/is (re-find #"IN \('public'\)" rewritten) "should contain simplified search_path")))

  (t/testing "column discovery query is rewritten with table name"
    (let [grafana-query "SELECT quote_ident(column_name) AS \"column\", data_type AS \"type\"\n    FROM information_schema.columns\n    WHERE\n      CASE WHEN array_length(parse_ident('sensor_readings'),1) = 2\n        THEN quote_ident(table_schema) = (parse_ident('sensor_readings'))[1]\n          AND quote_ident(table_name) = (parse_ident('sensor_readings'))[2]\n        ELSE quote_ident(table_name) = 'sensor_readings'\n          AND \n          quote_ident(table_schema) IN (\n          SELECT\n            CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END\n          FROM\n            generate_series(\n              array_lower(string_to_array(current_setting('search_path'),','),1),\n              array_upper(string_to_array(current_setting('search_path'),','),1)\n            ) as i,\n            string_to_array(current_setting('search_path'),',') s\n          )\n      END"
          rewritten (#'xtdb.pgwire/apply-grafana-rewrites grafana-query)]
      (t/is (not= grafana-query rewritten) "query should be rewritten")
      (t/is (re-find #"table_name\) = 'sensor_readings'" rewritten) "should contain table name")
      (t/is (re-find #"table_schema\) = 'public'" rewritten) "should default to public schema")))

  (t/testing "column discovery query handles schema-qualified names"
    (let [grafana-query "SELECT quote_ident(column_name) AS \"column\", data_type AS \"type\"\n    FROM information_schema.columns\n    WHERE\n      CASE WHEN array_length(parse_ident('myschema.mytable'),1) = 2\n        THEN quote_ident(table_schema) = (parse_ident('myschema.mytable'))[1]\n          AND quote_ident(table_name) = (parse_ident('myschema.mytable'))[2]\n        ELSE quote_ident(table_name) = 'myschema.mytable'\n          AND \n          quote_ident(table_schema) IN (\n          SELECT\n            CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END\n          FROM\n            generate_series(\n              array_lower(string_to_array(current_setting('search_path'),','),1),\n              array_upper(string_to_array(current_setting('search_path'),','),1)\n            ) as i,\n            string_to_array(current_setting('search_path'),',') s\n          )\n      END"
          rewritten (#'xtdb.pgwire/apply-grafana-rewrites grafana-query)]
      (t/is (re-find #"table_schema\) = 'myschema'" rewritten) "should extract schema")
      (t/is (re-find #"table_name\) = 'mytable'" rewritten) "should extract table name"))))
