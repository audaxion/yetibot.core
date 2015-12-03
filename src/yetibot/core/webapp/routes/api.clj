(ns yetibot.core.webapp.routes.api
  (:require
    [yetibot.core.webapp.views.common :as views]
    [yetibot.core.interpreter :refer [*chat-source*]]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.chat :refer [chat-data-structure *messaging-fns*]]
    [taoensso.timbre :refer [info warn]]
    ; [yetibot.core.adapters.campfire :refer [self]]
    [clojure.edn :as edn]
    [compojure.core :refer :all]))

(defn determine-messaging-fns-and-target
  "target should be a symbol, not a resolved var, so it can be dynamically bound"
  [chat-source]
  (let [adapter-ns (condp = (:adapter chat-source)
                     :slack 'yetibot.core.adapters.slack
                     :irc 'yetibot.core.adapters.irc
                     :campfire 'yetibot.core.adapters.campfire)]
    [(ns-resolve adapter-ns 'messaging-fns)
     ; e.g. 'yetibot.core.adapters.slack/*target*
     (symbol (str adapter-ns) "*target*")]))

(defn api [{:keys [chat-source text command token] :as params} req]
  (info "/api called with params:" params)
  (info "/api request:" req)
  (when-let [body (:body req)] (info "/api body:" (slurp body)))
  (cond
    (empty? chat-source) "chat-source parameter is required!"
    (and (empty? command) (empty? text)) "command or text parameter is required!"
    :else (if-let [chat-source (edn/read-string chat-source)]
            (do
              (info "chat-source" chat-source)
              (let [user {:username "api"}
                    room (:room chat-source)
                    res (or text (handle-unparsed-expr chat-source user command))
                    [messaging-fns *target*] (determine-messaging-fns-and-target chat-source)]
                (binding [*messaging-fns* @messaging-fns
                          *chat-source* chat-source]
                  (info "*messaging-fns*:" *messaging-fns*)
                  ; this is pretty gross. todo: refactor without runtime binding
                  ; could avoid by having chat-data-structure bind *target*
                  ; using (:room chat-source) if not bound.
                  (eval `(binding [~*target* ~room]
                           (~chat-data-structure ~res)
                           ~res)))))
            (str "invalid chat-source:" chat-source))))

(defroutes api-routes
  (GET "/api" [& params :as req] (api params req))
  (POST "/api" [& params :as req] (api params req)))
