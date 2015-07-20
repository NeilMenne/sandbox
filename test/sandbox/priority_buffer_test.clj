(ns sandbox.priority-buffer-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [sandbox.priority-buffer :refer [priority-buffer]]))

(deftest priority-buffer-test
  (let [b (priority-buffer 5 #(> % 50))
        c (async/chan b (filter number?))
        _ (async/<!! (async/onto-chan c [1 2 3 51 52 53] false))]
    (is (= '(52 51 1 2 3) (take 5 (repeatedly #(async/<!! c)))))))
