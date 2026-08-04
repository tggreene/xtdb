(ns xtdb.pgwire.grafana-test
  (:require [clojure.test :as t]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-node)

(t/deftest comment-only-query-test
  (t/testing "SQL comment-only queries don't throw (pgx driver sends '-- ping')"
    ;; The in-process SQL parser rejects a comment-only string; pgwire handles it gracefully.
    (with-open [conn (jdbc/get-connection tu/*node*)]
      (t/is (some? (jdbc/execute! conn ["-- ping"])))))

  (t/testing "double-dash inside string literals is not treated as a comment"
    (t/is (= [{:bar "--foo"}]
             (xt/q tu/*node* "SELECT '--foo' AS bar")))))

(t/deftest datasource-server-version-test
  (t/is (seq (xt/q tu/*node* "SELECT current_setting('server_version_num') AS v"))
        "Grafana probes the server version via current_setting"))

(t/deftest datasource-timescaledb-probe-test
  (t/is (= []
           (xt/q tu/*node* "SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'"))
        "Grafana probes pg_extension for timescaledb - resolves to empty, not an error"))

(t/deftest datasource-table-listing-test
  (xt/execute-tx tu/*node* [[:sql "INSERT INTO foo (_id) VALUES (1)"]])
  (t/is (seq (xt/q tu/*node*
                   "SELECT quote_ident(table_name) AS \"table\", quote_ident(table_schema) AS \"schema\"
                    FROM information_schema.tables
                    WHERE quote_ident(table_schema) NOT IN ('information_schema', 'pg_catalog')"))
        "Grafana lists tables via information_schema.tables"))

(t/deftest datasource-column-listing-test
  (xt/execute-tx tu/*node* [[:sql "INSERT INTO foo (_id, name) VALUES (1, 'a')"]])
  (t/is (seq (xt/q tu/*node*
                   "SELECT column_name, data_type
                    FROM information_schema.columns
                    WHERE quote_ident(table_schema) = 'public' AND quote_ident(table_name) = 'foo'"))
        "Grafana lists columns via information_schema.columns"))

(def ^:private search-path-schema-constraint
  ;; Grafana resolves search_path into schema names with this subquery, and
  ;; embeds it in both its table- and column-discovery queries.
  "quote_ident(table_schema) IN (
     SELECT CASE WHEN trim(s[i]) = '\"$user\"' THEN user ELSE trim(s[i]) END
     FROM generate_series(
            array_lower(string_to_array(current_setting('search_path'), ','), 1),
            array_upper(string_to_array(current_setting('search_path'), ','), 1)
          ) AS i,
          string_to_array(current_setting('search_path'), ',') s
   )")

(t/deftest expr-derived-table-alias-binds-column-test
  (t/testing "an aliased expression in FROM is addressable as a column, as in PG"
    (t/is (= [{:s ["a" "b"]}]
             (xt/q tu/*node* "SELECT s FROM string_to_array('a,b', ',') s")))

    (t/is (= [{:x ["a" "b"]}]
             (xt/q tu/*node* "SELECT x FROM string_to_array('a,b', ',') s (x)"))
          "an explicit table projection still wins")

    (t/is (= [{:s "a"}]
             (xt/q tu/*node* "SELECT s[i] AS s FROM generate_series(1, 1) i, string_to_array('a,b', ',') s"))
          "subscripting the alias across a cross join - the shape Grafana emits")))

(t/deftest datasource-search-path-schema-constraint-test
  (xt/execute-tx tu/*node* [[:sql "INSERT INTO foo (_id, name) VALUES (1, 'a')"]])

  (t/is (= [{:schema "public"}]
           (xt/q tu/*node* (str "SELECT DISTINCT quote_ident(table_schema) AS schema
                                 FROM information_schema.tables
                                 WHERE " search-path-schema-constraint)))
        "the constraint resolves search_path to 'public' rather than matching nothing")

  (t/is (seq (xt/q tu/*node* (str "SELECT quote_ident(column_name) AS column, data_type AS type
                                   FROM information_schema.columns
                                   WHERE CASE WHEN array_length(parse_ident('foo'), 1) = 2
                                            THEN quote_ident(table_schema) = (parse_ident('foo'))[1]
                                              AND quote_ident(table_name) = (parse_ident('foo'))[2]
                                            ELSE quote_ident(table_name) = 'foo'
                                              AND " search-path-schema-constraint "
                                          END")))
        "Grafana's real column-discovery query finds columns - it returned zero rows before #5170's gap was closed"))
