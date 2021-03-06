(ns yetibot.core.chat
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.string :refer [blank?]]
    [yetibot.core.util.format :as fmt]))

; The chat adapter should set this before firing off command handlers.
; expected keys are :paste, :msg, :join, :leave
(def ^:dynamic *messaging-fns*)

(defn- mk-sender [sender-key]
  (fn [msg]
    (let [msg (str msg)]
      ((sender-key *messaging-fns*) (if (empty? msg) "No results" msg)))))

(def send-msg (mk-sender :msg))
(def send-paste (mk-sender :paste))
(defn join [room] ((:join *messaging-fns*) room))
(defn leave [room] ((:leave *messaging-fns*) room))
(defn rooms [] ((:rooms *messaging-fns*)))
(defn set-room-broadcast [room broadcast?] ((:set-room-broadcast *messaging-fns*) room broadcast?))

(def max-msg-count 30)

(defn send-msg-for-each [msgs]
  (doseq [m (take max-msg-count msgs)] (send-msg m))
  (when (> (count msgs) max-msg-count)
    (send-msg (str "Results truncated. There were "
                   (count msgs)
                   " results but I only sent "
                   max-msg-count "."))))

(defn contains-image-url-lines?
  "Returns true if the string contains an image url on its own line, separated from
   other characters by a newline"
  [string]
  (not (empty? (filter #(re-find (re-pattern (str "(?m)^http.*\\." %)) string)
                       ["jpeg" "jpg" "png" "gif"]))))

(defn should-send-msg-for-each?  [d formatted]
  (and (coll? d)
       (<= (count d) 30)
       (re-find #"\n" formatted)
       (contains-image-url-lines? formatted)))

(defn chat-data-structure
  "Formatters to send data structures to chat.
   If `d` is a nested data structure, it will attempt to recursively flatten
   or merge (if it's a map)."
  [d]
  (when-not (:suppress (meta d))
    (let [[formatted flattened] (fmt/format-data-structure d)]
      (debug "formatted is" formatted)
      (debug "flattened is" flattened)
      (cond
        ; send each item in the coll as a separate message if it contains images and
        ; the total length of the collection is less than 20
        (should-send-msg-for-each? d formatted) (send-msg-for-each flattened)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) (send-paste formatted)
        ; send as regular message
        :else (send-msg formatted)))))

; Passive chatters (those that are not responding to a command from a single
; chat interface) need to be able to broadcast messages to any sources, so when
; a namespace is configured it should add itself to this list.
(defonce active-chat-namespaces (atom []))

(defn register-chat-adapter [n]
  (swap! active-chat-namespaces conj n))

(defn send-msg-to-all-adapters [msg]
  (prn "send msg to all" msg)
  (prn @active-chat-namespaces)
  (doseq [n @active-chat-namespaces]
    (when-let [send-to-all (deref (ns-resolve n 'send-to-all))]
      (send-to-all msg))))


; TODO: move hooks/suppress right here

(defn with-target
  "Add target meta data to the data structure to instruct `chat` where to send it"
  [target data-structure]
  (with-meta data-structure {:target target}))
