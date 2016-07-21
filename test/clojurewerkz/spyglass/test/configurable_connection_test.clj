(ns clojurewerkz.spyglass.test.configurable-connection-test
  (:require [clojurewerkz.spyglass.client :as c])
  (:use clojure.test)
  (:import [net.spy.memcached ClientMode FailureMode]))

(c/set-log-level! "WARNING")
(def memcached-host (or (System/getenv "MEMCACHED_HOST")
                        "localhost:11211"))

(deftest test-to-failure-mode
  (are [alias mode] (is (= (c/to-failure-mode alias) mode))
    "redistribute" FailureMode/Redistribute
    "retry"        FailureMode/Retry
    "cancel"       FailureMode/Cancel
    :redistribute  FailureMode/Redistribute
    :retry         FailureMode/Retry
    :cancel        FailureMode/Cancel))

(deftest test-to-client-mode
  (are [alias mode] (is (= (c/to-client-mode alias) mode))
     "dynamic" ClientMode/Dynamic
     "static"  ClientMode/Static
     :dynamic  ClientMode/Dynamic
     :static   ClientMode/Static))

(deftest test-text-connection-factory
  (let [cf (c/text-connection-factory :failure-mode :redistribute)]
    (is (= FailureMode/Redistribute (.getFailureMode cf))))
  (let [cf (c/text-connection-factory :failure-mode :cancel)]
    (is (= FailureMode/Cancel (.getFailureMode cf)))))

(deftest test-connection-with-custom-failure-mode
  (let [conn (c/text-connection
               memcached-host
               (c/text-connection-factory :failure-mode :redistribute))]
    (c/set conn "abc000" 3000 "1")
    (is (= (c/get conn "abc000") "1"))))

(deftest test-custom-client-mode
  (let [cf (c/text-connection-factory :client-mode :static)]
    (is (= ClientMode/Static (.getClientMode cf))))
  (let [cf (c/text-connection-factory :client-mode :dynamic)]
    (is (= ClientMode/Dynamic (.getClientMode cf)))))

(deftest test-connection-with-custom-client-mode
  (let [conn (c/text-connection
              memcached-host
              (c/text-connection-factory :client-mode :static))]
    (c/set conn "abc000" 3000 "1")
    (is (= (c/get conn "abc000") "1"))))
