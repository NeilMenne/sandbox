(ns sandbox.priority-buffer
  "slightly expanded functionality compared to the sliding-buffer that
  comes with core.async:
  . truthy items go to the front OR refuse to add themselves to the buffer
    if it's already full
  . falsey items go to the back AND will bump an item off of the front to
    fit onto the buffer."
  (:require [clojure.core.async.impl.protocols :as impl])
  (:import [java.util LinkedList]))

(deftype PriorityBuffer [^LinkedList buf ^long n priority-fn]
  impl/UnblockingBuffer
  impl/Buffer
  (full? [this]
    false)
  (remove! [this]
    (.removeLast buf))
  (add!* [this item]
    (let [s (.size buf)]
      (if (priority-fn item)
        (when-not (= s n)
          (.addLast buf item))
        (do
          (when (= s n)
            (impl/remove! this))
          (.addFirst buf item))))
    this)
  clojure.lang.Counted
  (count [this]
    (.size buf)))

(defn priority-buffer
  "provide a function to that takes the items"
  [n priority-fn]
  (PriorityBuffer. (LinkedList.) n priority-fn))
