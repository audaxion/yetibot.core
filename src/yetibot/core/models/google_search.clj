(ns yetibot.core.models.google-search
  (:require
    [yetibot.core.config :refer [get-config conf-valid?]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def config (get-config :yetibot :models :google-search))
(def endpoint "https://www.googleapis.com/customsearch/v1")
(def configured? (conf-valid? config))

(defn- format-result [result]
  [(-> (get-in result [:pagemap :cse_image])
       rand-nth
       :src)
   #_(:title result)
   #_(:link result)])

(defn- fetch-image [q n]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:key (:key config) :cx (:cx config)
                                 :num n :q q :safe "high"}))]
    (get-json uri)))

(defn image-search
  ([q] (image-search q 50))
  ([q n]
   (map format-result
        (-> (fetch-image q n) :items))))
