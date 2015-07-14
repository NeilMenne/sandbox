(ns sandbox.core
  (:require [clojure.core.async :as async]))

(def transduce-simple
  "if the numbers are even, increment them, and keep them in a vec"
  (transduce (comp (filter even?) (map inc))
             conj [] (range 0 5)))

(defn square
  [x]
  (* x x))

(def simple-xf
  (comp (filter odd?) (map dec) (map square)))

(def transduce-via-existing-xform
  "using a defined transform is easy"
  (transduce simple-xf + 0 (range 0 1000)))

(defn test-num-chan
  [input expected-value]
  (let [num-chan (async/chan 5 (comp (filter even?) (map str)))
        v (future (async/<!! num-chan))
        _  (async/>!! num-chan input)
        ret (deref v 50 :timed-out)]
    (= ret expected-value)))

(def working-as-intended
  (and (test-num-chan 5 :timed-out)
       (test-num-chan 6 "6")))

(defn drop-oldest-chan
  []
  (let [buf (async/sliding-buffer 5)
        c (async/chan buf)]
    (async/onto-chan c (range 0 11))
    (Thread/sleep 10)
    (let [out #(async/<!! c)
          ret (do (take 5 (repeatedly out)))]
      (= '(6 7 8 9 10) ret))))

;;; close out with a more interesting example that takes maps
;;; representing some sort of business object, mutating and filtering
;;; it as some might do, first process takes from channel c does
;;; something, and then passes to the final process via channel d
(def db (atom {}))
(defn sanitize-id
  [m]
  (if (contains? m :id)
    (update m :id str)
    m))

(defn dup?
  [m]
  (not (get @db (:id m))))

(def channel-c (async/chan 10 (comp (map sanitize-id) (filter dup?))))
(def channel-d (async/chan 10))

(defn c->d
  "a simple process that takes from c, mutates the data, and puts into
  channel d"
  []
  (let [ms (map (fn [x] {:id x :as-num x}) (range 0 5))
        _  (async/onto-chan channel-c ms)]
    (Thread/sleep 10)
    (when-let [cs (do (take 5 (repeatedly #(deref (future (async/<!! channel-c)) 10 nil))))]
      (let [cs (map #(assoc % :new-data (rand)) cs)]
        (async/onto-chan channel-d cs)
        (Thread/sleep 10)))))

(defn store-row
  "store a single row"
  [some-db r]
  (assoc some-db (:id r) r))

(defn take-one-from-d
  []
  (deref (future (async/<!! channel-d)) 10 nil))

(defn store-in-db
  "final process: stores results in db atom; re-running
  transform-and-pipe won't end up taking anything from channel-c
  because of the filter"
  []
  (loop [v (take-one-from-d)]
    (if v
      (do
        (prn v)
        (swap! db store-row v)
        (recur (take-one-from-d)))
      @db)))

; (c->d)
; (store-in-db)

(def drain-buf (async/sliding-buffer 10))
(def drain-channel (async/chan drain-buf))

(def other-buf (async/sliding-buffer 100))
(def other-channel (async/chan other-buf))

(defn drain
  "draining a buffered channel (requires an actual buffer, not a
  number for the channel configuration)"
  []
  (loop [c (count drain-buf)
         ret []]
    (if (> c 0)
     (let [v (async/<!! drain-channel)]
       (do
         (recur (dec c)
                (conj ret v))))
     ret)))

(defn do-drain
  []
  (async/onto-chan drain-channel (range 0 50) false)
  (Thread/sleep 10)
  (drain))

(defn drain!
  "drain some channel, c"
  [c]
  (loop [ret []
         v (first (async/alts!! [c] :default :none-avail))]
    (if (= v :none-avail)
      ret
      (recur (conj ret v)
             (first (async/alts!! [c] :default :none-avail))))))
