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
(def out (async/chan))
(def mix (async/mix out))
(def in1 (async/chan 100))
(def in2 (async/chan 100))

(defn admix-all []
  (async/admix mix in1)
  (async/admix mix in2))

(defn sub-in1
  []
  (async/go-loop []
    (async/<! (async/timeout 300))
    (async/>! in1 {:which "in1"
                   :val (rand-int 1000)})
    (recur)))

(defn sub-in2
  []
  (async/go-loop []
    (async/<! (async/timeout 300))
    (async/>! in2 {:which "in2"
                   :val (rand-int 1000)})
    (recur)))

(defn drain-only-one-from-mix
  []
  (let [{:keys [which] :as f} (async/<!! out)
        chan-name (if (= which "in1")
                    in1 in2)]
    (async/toggle mix {chan-name {:solo :pause}})
    (let [all-in (conj (drain! out) f) ; don't forget to use the first value you took
          _ (async/toggle mix {chan-name {:solo false}})
          ret (transduce (comp (map :val)
                               (filter #(> % 200)))
                         + all-in)]
      {which ret})))

(defn drain-one-proc
  []
  (admix-all)
  (sub-in1)
  (sub-in2)
  (async/go-loop []
    (async/<! (async/timeout 3000))
    (println (drain-only-one-from-mix))
    (recur)))

(def pub-chan (async/chan))
(def pub (async/pub pub-chan :type))
(defn subscriber-process
  "provide a type of message you'd like this process to subscribe to"
  [which-type p f]
  (let [s (async/chan)
        _ (async/sub p which-type s)]
    (async/go-loop []
      (let [val (async/<! s)]
        (when val
          (if (map? val)
            (f (which-type val))
            (f val))
          (recur))))))

(defn sub-two
  []
  (subscriber-process :request pub println)
  (subscriber-process :log pub println)
  (async/>!! pub-chan {:type :request :request "the criteria was met"})
  (async/>!! pub-chan {:type :log :log "doesn't really matter; i'm subbed to completely different things"})
  ;; both subprocesses will get the nil from channel close and
  ;; terminate
  (async/close! pub-chan))

;; more complicated example
(defn topic-fn
  [val]
  (cond (number? val)
        :just-a-number

        :else
        :other))

(defn buffer-fn
  "if you know at pub creation time that certain subs will need the
  buffers (but might not be setup that way themselves) you can provide
  custom buffer functions"
  [topic]
  (case topic
    :just-a-number nil
    :other (async/dropping-buffer 5)
    (async/sliding-buffer 50)))

(def pc (async/chan))
(def p (async/pub pc topic-fn buffer-fn))
(defn use-the-custom-pub
  []
  (subscriber-process :just-a-number p (let [n (atom 0)]
                                         (fn [v]
                                           (swap! n + v)
                                           (println @n))))
  (subscriber-process :other p println)
  (async/onto-chan pc (range 0 100) false)
  (async/>!! pc :trig-other))
