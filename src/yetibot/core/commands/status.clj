(ns yetibot.core.commands.status
  (:require
    [yetibot.core.util :refer [with-fresh-db]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.status :as model]
    [clj-time [core :refer [ago minutes hours days weeks years months]]]
    [inflections.core :refer [plural]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def empty-msg "No one has set their status")

(defn show-status
  "status # show statuses in the last 8 hours"
  [{:keys [chat-source]}]
  (model/format-sts (model/status-since (pr-str chat-source) (-> 8 hours ago))))

(defn show-status-since
  "status since <n> <minutes|hours|days|weeks|months> ago # show status since a given time"
  [{[_ n unit] :match chat-source :chat-source}]
  (let [unit (plural unit) ; pluralize if singular
        unit-fn (ns-resolve 'clj-time.core (symbol unit))
        n (read-string n)]
    (if (number? n)
      (model/format-sts (model/status-since (pr-str chat-source) (-> n unit-fn ago)))
      (str n " is not a number"))))

(defn set-status
  "status <message> # update your status"
  [{:keys [match chat-source user] :as args}]
  (let [str-chat-source (pr-str chat-source)]
    (info "add status in" str-chat-source ":" match)
    (model/add-status user str-chat-source match)
    (with-fresh-db (show-status args))))

(cmd-hook #"status"
          #"since (.+) (minutes*|hours*|days*|weeks*|months*)( ago)*" show-status-since
          #".+" set-status
          _ show-status)
