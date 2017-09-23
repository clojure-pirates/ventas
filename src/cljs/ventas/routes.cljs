(ns ventas.routes
  (:require [clojure.string :as str]
            [bidi.bidi :as bidi]
            [ventas.utils.logging :refer [trace debug info warn error]]
            [accountant.core :as accountant]
            [ventas.page :as page]
            [reagent.session :as session]))

(defn route-parents
  ":admin.users.something -> [:admin :admin.users :admin.users.something]"
  [route]
  (debug "route-parents route" route)
  (into [] (map #(keyword (str/join "." %))
                (reduce (fn [acc i]
                          (conj acc (conj (vec (last acc)) i)))
                        []
                        (drop-last (str/split (name route) #"\."))))))

(defn- index-urls
  "Creates a [route -> url] map"
  [routes]
  (reduce (fn [acc {:keys [route url] :as item}]
            (assoc acc route url))
          {}
          routes))

(defn- prepare-routes [routes]
  (let [indexed-urls (index-urls routes)]
    (map (fn [route]
           (let [parent (last (route-parents (:route route)))
                 parent-url (indexed-urls parent)]
             (update route :url #(cond
                                   (= parent-url "") %1
                                   (string? %1) (str "/" %1)
                                   :else (vec (concat ["/"] %))))))
         routes)))

(defn- reducer [acc {:keys [route url] :as item} indexed-urls]
  (let [parents (route-parents route)
        _ (debug "parents" parents "url" url "route" route "parent" (map #(% indexed-urls) parents))]
    (if (seq parents)
      (update-in acc (map #(% indexed-urls) parents) assoc url {"" route})
      (assoc acc url {"" route}))))

(defn compile-routes [routes]
  (debug "compile-routes" routes)
  (let [routes (prepare-routes routes)
        _ (debug "routes" routes)
        indexed-urls (index-urls routes)
        _ (debug "indexed-urls" indexed-urls)]
    ["" (-> (reduce (fn [acc item]
                       (debug "calling reducer" acc item)
                       (reducer acc item indexed-urls))
                     {}
                     routes)
             (assoc true :not-found))]))

(def route-data
  (atom []))

(def routes (atom (compile-routes @route-data)))

(defn define-route! [name {:keys [component] :as attrs}]
  (debug "define-route!" name attrs)
  (swap! route-data conj (assoc attrs :route name))
  (reset! routes (compile-routes @route-data))
  (when component
    (defmethod page/pages name []
      [component])))

(defn define-routes! [new-routes]
  (swap! route-data concat new-routes)
  (reset! routes (compile-routes @route-data)))

(defn find-route
  "Finds a route by its id"
  [id]
  (first (filter #(= (:route %) id) @route-data)))

(defn path-for
  "bidi/path-for wrapper"
  [& args]
  (let [path (apply bidi/path-for @routes args)]
    (when-not path
      (error "Route not found" args))
    path))

(defn match-route
  "bidi/match-route wrapper"
  [& args]
  (debug @route-data)
  (debug @routes)
  (apply bidi/match-route @routes args))

(defn go-to [& args]
  (when-let [path (apply path-for args)]
    (accountant/navigate! path)))

(defn current
  "Returns the current route"
  []
  (session/get :route))