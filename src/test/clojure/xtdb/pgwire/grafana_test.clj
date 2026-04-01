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

(t/deftest test-grafana-function-composition
  (t/testing "quote_ident on parse_ident round-trip"
    (t/is (= [{:x "\"My Schema\""}]
             (xt/q tu/*node* "SELECT quote_ident(parse_ident('\"My Schema\".table_name')[1]) AS x"))
          "quoted part from parse_ident re-quoted by quote_ident")

    (t/is (= [{:x "table_name"}]
             (xt/q tu/*node* "SELECT quote_ident(parse_ident('\"My Schema\".table_name')[2]) AS x"))
          "unquoted part from parse_ident stays unquoted through quote_ident"))

  (t/testing "Grafana table discovery query pattern"
    (t/is (= [{:x " public"}]
             (xt/q tu/*node*
                   "SELECT string_to_array(current_setting('search_path'), ',')[2] AS x"))
          "extracting second element from search_path")))
