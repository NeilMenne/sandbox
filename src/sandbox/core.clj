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

(def drain-buf (async/sliding-buffer 10))
(def drain-channel (async/chan drain-buf))

(defn poll!
  "non-blocking take from the provided channels"
  [& chans]
  (first (async/alts!! chans :default nil)))

(defn drain!
  "take n (or all things if no n specified) things from a channel"
  ([chan] (take-while some? (repeatedly #(poll! chan))))
  ([chan n]
   (take n (drain! chan))))

(defn drain-example
  "I have found it useful to sometimes take many things from a channel
  and get them out of the channel at once, so I wrote drain! to do so"
  []
  (async/onto-chan drain-channel (range 0 50) false)
  (Thread/sleep 10)
  (drain! drain-channel))

;;; subprocess example
(defn sub-process
  [f c & [e-chan]]
  (async/go-loop []
    (let [v (async/<! c)]
      (when (and v (not= v :terminate))
        (try (f v)
             (catch Exception e
               (when e-chan
                 (async/go (async/>! e-chan e)))))
        (recur)))))

(def proc-chan (async/chan (async/sliding-buffer 100)))

(defn sub-process-example
  []
  (sub-process println proc-chan)
  (async/>!! proc-chan "print me!")
  (async/<!! (async/timeout 20))
  (async/>!! proc-chan :terminate))

;;; TODO: mix example, mult example
