(ns web-visitor.aggregate-test
  (:require [clojure.test :refer :all]
            [web-visitor.aggregate :as aggregate]))

(deftest increment-many
  (testing "Increment many collects the frequencies of each item at the given path."
    (is (= (aggregate/increment-many {:hello :world} [:things] [:a :b :c :a] [:count])
          {:hello :world, :things {:a 2, :b 1, :c 1}}))))
