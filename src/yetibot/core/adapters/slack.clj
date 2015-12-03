(ns yetibot.core.adapters.slack
  (:require
    [gniazdo.core :as ws]
    [clojure.string :as s]
    [yetibot.core.interpreter :refer [*chat-source*]]
    ; [http.async.client :as c]
    ; [org.httpkit.client :as http]
    [yetibot.core.models.users :as users]
    [yetibot.core.util.http :refer [html-decode]]
    [clj-slack
     [users :as slack-users]
     [chat :as slack-chat]
     [channels :as channels]
     [rtm :as rtm]]
    [slack-rtm.core :as slack]
    [taoensso.timbre :as log]
    [yetibot.core.config :refer [update-config get-config config-for-ns
                                 reload-config conf-valid?]]
    [yetibot.core.handler :refer [handle-raw]]
    [yetibot.core.chat :refer [chat-data-structure send-msg-for-each
                               register-chat-adapter] :as chat]))

(defn all-config
  "Can be a single slack configuration map or a collection of multiple
   configurations."
  [] (get-config :yetibot :adapters :slack))

;; dynamic vars are per-slack config
(defonce ^{:dynamic true} *conn* (atom nil))
(defonce ^{:dynamic true} *config* nil)

(defonce hash-to-conn-and-config (atom {}))

(defn self
  "Slack acount for yetibot from `rtm.start` (represented at (-> @*conn* :start)).
   You must call `start` in order to define `*conn*`."
  []
  (-> @*conn* :start :self))

(defn determine-conn-and-config-from-chat-source-hash []
  (log/info "*conn* not set; find using" *chat-source*)
  (log/info (keys @hash-to-conn-and-config))
  (get @hash-to-conn-and-config (:conn-hash *chat-source*)))

(defn slack-config []
  (let [c *config* ]
    {:api-url (:endpoint c) :token (:token c)}))

(def ^{:dynamic true
       :doc "the channel or user that a message came from"} *target*)

(defn rooms [] (:rooms *config*))

;;;;

(def adapter :slack)

(defn chat-source [channel] {:adapter adapter :room channel
                             :conn-hash (hash *config*)})

;; send-msg and send-paste must bind *config* and *conn* themselves if it is
;; missing, as in the case of API calls. this is getting pretty ridiculous.

;; this results in: "No matching ctor found for class ;; yetibot.core.adapters.slack$mk_sender_with_verified_bindings$fn__14051"
; (defn mk-sender-with-verified-bindings [f]
;   (fn [msg]
;     (if (and *config* @*conn*)
;       (f msg)
;       (let [[conn config] (determine-conn-and-config-from-chat-source-hash)]
;         (binding [*conn* conn
;                   *config* config]
;           (f msg))))))

(defn send-msg [msg]
  (let [f (fn [msg] (slack-chat/post-message
                      (slack-config) *target* msg
                      {:unfurl_media "true" :as_user "true"}))]
    (if (and *config* @*conn*)
      (f msg)
      (let [[conn config] (determine-conn-and-config-from-chat-source-hash)]
        (binding [*conn* conn
                  *config* config]
          (f msg))))))

(defn send-paste [msg]
  (let [f (fn [msg]
            (slack-chat/post-message
              (slack-config) *target* ""
              {:unfurl_media "true" :as_user "true"
               :attachments [{:pretext "" :text msg}]}))]
    (if (and *config* @*conn*)
      (f msg)
      (let [[conn config] (determine-conn-and-config-from-chat-source-hash)]
        (binding [*conn* conn
                  *config* config]
          (f msg))))))

(def messaging-fns
  {:msg send-msg
   :paste send-paste
   :join nil
   :leave nil
   :set-room-broadcast nil
   :rooms rooms})

;; formatting

(defn unencode-message
  "Slack gets fancy with URL detection, channel names, user mentions, as
   described in https://api.slack.com/docs/formatting. This can break support
   for things where YB is expecting a URL (e.g. configuring Jenkins), so strip
   it for now. Replaces <X|Y> with Y."
  [body]
  (-> body
    (s/replace #"\<(.+)\|(.+)\>" "$2")
    html-decode))

;; events

(defn on-channel-join [e]
  (log/info "channel join" e)
  (let [cs (chat-source (:channel e))
        user-model (users/get-user cs (:user e))]
    (handle-raw cs user-model :enter nil)))


(defn on-channel-leave [e]
  (log/info "channel leave" e)
  (let [cs (chat-source (:channel e))
        user-model (users/get-user cs (:user e))]
    (handle-raw cs user-model :leave nil)))

(defn handle-message [event]
  ; don't listen to yetibot's own messages
  (when (not= (:id (self)) (:user event))
    (let [channel (:channel event)
          cs (chat-source channel)
          user-model (users/get-user cs (:user event))]
      (binding [*target* channel
                yetibot.core.chat/*messaging-fns* messaging-fns]
        (handle-raw cs
                    user-model
                    :message
                    (:text event))))))

(defn on-message [event]
  (log/info "message" event)
  (if-let [subtype (:subtype event)]
    ; handle the subtype
    (condp = subtype
      "channel_join" (on-channel-join event)
      "channel_leave" (on-channel-leave event)
      "me_message" (handle-message event)
      ; do nothing if we don't understand
      nil)
    (handle-message event)))

(defn on-hello [event]
  (log/info "hello" event))

(defn on-connect [e]
  (log/info "connect" e))

(defn on-close [status]
  (log/info "close" status))

(defn on-error [exception]
  (log/error "error" exception))

(defn handle-presence-change [e]
  (let [active? (= "active" (:presence e))
        id (:user e)
        source {:adapter adapter}]
    (log/debug id "presence change active?=" active?)
    (users/update-user source id {:active? active?})))

(defn on-presence-change [e]
  (log/debug "presence changed" e)
  (handle-presence-change e))

(defn on-manual-presence-change [e]
  (log/debug "manual presence changed" e)
  (handle-presence-change e))

(defn on-channel-joined
  "Fires when yetibot gets invited and joins a channel or group"
  [e]
  (log/debug "channel joined" e)
  (let [c (:channel e)
        cs (chat-source (:id c))
        user-ids (:members c)]
    (log/debug "adding chat source" cs "for users" user-ids)
    (dorun (map #(users/add-chat-source-to-user cs %) user-ids))))

(defn on-channel-left
  "Fires when yetibot gets kicked from a channel or group"
  [e]
  (log/debug "channel left" e)
  (let [c (:channel e)
        cs (chat-source c)
        users-in-chan (users/get-users cs)]
    (log/debug "remove users from" cs (map :id users-in-chan))
    (dorun (map (fn [u] (users/remove-user cs (:id u))) users-in-chan))))

;; users

(defn filter-chans-or-grps-containing-user [user-id chans-or-grps]
  (filter #((-> % :members set) user-id) chans-or-grps))

(defn reset-users-from-conn []
  (let [groups (-> @*conn* :start :groups)
        channels (-> @*conn* :start :channels)
        users (-> @*conn* :start :users)]
    (dorun
      (map
        (fn [{:keys [id] :as user}]
          (let [filter-for-user (partial filter-chans-or-grps-containing-user id)
                ; determine which channels and groups the user is in
                chans-or-grps-for-user (concat (filter-for-user channels)
                                               (filter-for-user groups))
                active? (= "active" (:presence user))
                ; turn the list of chans-or-grps-for-user into a list of chat sources
                chat-sources (set (map (comp chat-source :id) chans-or-grps-for-user))
                ; create a user model
                user-model (users/create-user (:name user) active? (assoc user :mention-name (str "<@" (:id user) ">")))]
            (if (empty? chat-sources)
              (users/add-user-without-room adapter user-model)
              (dorun
                ; for each chat source add a user individually
                (map (fn [cs] (users/add-user cs user-model)) chat-sources)))))
        users))))


;; start/stop

(defn stop []
  (when @*conn*
    (log/info "Closing" @*conn*)
    (slack/send-event (:dispatcher @*conn*) :close))
  (reset! *conn* nil))

(defn start []
  (stop)
  (let [ac (all-config)
        ; make it a vector if it isn't already
        configs (if (map? ac) [ac] ac)]
    (dorun
      (map
        (fn [config]
          (binding [*config* config
                    *conn* (atom nil)]
            (log/info "setting up" *config*)
            (reset! *conn* (slack/connect (slack-config)
                                          :on-connect on-connect
                                          :on-error on-error
                                          :on-close on-close
                                          :presence_change on-presence-change
                                          :channel_joined on-channel-joined
                                          :group_joined on-channel-joined
                                          :channel_left on-channel-left
                                          :group_left on-channel-left
                                          :manual_presence_change on-manual-presence-change
                                          :message on-message
                                          :hello on-hello))
            (swap! hash-to-conn-and-config conj {(hash *config*) [*conn* *config*]})
            (reset-users-from-conn)))
        configs))))

(defn list-channels [] (channels/list (slack-config)))

;; can't join rooms as a bot - must be invited
; (dorun
;     (map (fn [[channel-name channel-config]]
;            (log/info "join" channel-name
;                      (channels/join (slack-config) channel-name)))
;          (:rooms (config))))
