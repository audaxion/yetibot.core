(ns yetibot.core.models.bing-search
  (:require
    [yetibot.core.config :refer [get-config conf-valid?]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def config (get-config :yetibot :models :bing-search))
(def auth {:user "" :password (:key config)})
(def endpoint "https://api.datamarket.azure.com/Bing/Search/Image")
(def configured? (conf-valid? config))

(def ^:private format-result :MediaUrl)

(defn image-search [q]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:Query (str \' q \')
                                 :Adult (str \' "Strict" \')
                                 :$format "json"}))
        results (get-json uri auth)]
    (map format-result (-> results :d :results))))
