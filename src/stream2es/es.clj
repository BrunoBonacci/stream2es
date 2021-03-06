(ns stream2es.es
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [slingshot.slingshot :refer [try+ throw+]]
            [stream2es.log :as log]))

(defn components [url]
  (let [u (java.net.URL. url)
        [_ index type id] (re-find
                           #"/*([^/]+)/?([^/]+)?/?([^/]+)?"
                           (.getPath u))]
    {:proto (.getProtocol u)
     :host (.getHost u)
     :port (.getPort u)
     :index index
     :type type
     :id id}))

(defn base-url [full]
  (let [u (components full)]
    (apply format "%s://%s:%s" ((juxt :proto :host :port) u))))

(defn index-url [url]
  (let [{:keys [proto host port index]} (components url)]
    (format "%s://%s:%s/%s" proto host port index)))

(defn put
  ([url data]
     (log/trace "PUT" (count (.getBytes data)) "bytes")
     (http/put url {:body data})))

(defn post
  ([url data]
     (log/trace "POST" (count (.getBytes data)) "bytes")
     (http/post url {:body data})))

(defn delete [url]
  (log/info "delete index" url)
  (http/delete url {:throw-exceptions false}))

(defn exists? [url]
  (try
    (http/get (format "%s/_mapping" (index-url url)))
    (catch Exception _)))

(defn error-capturing-bulk [url items serialize-bulk]
  (let [resp (json/decode (:body (post url (serialize-bulk items))) true)]
    (->> (:items resp)
         (map-indexed (fn [n obj]
                        (when (contains? (val (first obj)) :error)
                          (spit (str "error-"
                                     (:_id (val (first obj))))
                                (with-out-str
                                  (prn obj)
                                  (println)
                                  (prn (nth items n))))
                          obj)))
         (remove nil?)
         count)))

(defn scroll*
  "One set of hits mid-scroll."
  [url id ttl]
  (try+
   (let [resp (http/get
               (format "%s/_search/scroll" url)
               {:body id
                :query-params {:scroll ttl}})]
     (json/decode (:body resp) true))
   (catch Object {:keys [body]}
     (cond
      (re-find #"SearchContextMissingException" body)
      (throw+ {:type ::search-context-missing})
      :else (throw+ {:type ::wat})))))

(defn scroll
  "lazy-seq of hits from on originating scroll_id."
  [url id ttl]
  (let [resp (scroll* url id ttl)
        hits (-> resp :hits :hits)
        new-id (:_scroll_id resp)]
    (lazy-seq
     (when (seq hits)
       (cons (first hits) (concat (rest hits) (scroll url new-id ttl)))))))

(defn scan1
  "Set up scroll context."
  [url query ttl size]
  (let [resp (http/get
              (format "%s/_search" url)
              {:body query
               :query-params
               {:search_type "scan"
                :scroll ttl
                :size size
                :fields "_source,_routing,_parent"}})]
    (json/decode (:body resp) true)))

(defn scan
  "Client entry point. Returns a scrolling lazy-seq of hits."
  [url query ttl size]
  (let [resp (scan1 url query ttl size)]
    (scroll (base-url url) (:_scroll_id resp) ttl)))

(defn idx-meta [url suffix]
  (let [resp (-> (format "%s/%s" url suffix)
                 http/get
                 :body
                 (json/decode true))
        index (:index (components url))]
    (if index
      (-> (resp (keyword index)) first val)
      resp)))

(defn mapping [url]
  (idx-meta url "_mapping"))

(defn settings [url]
  (idx-meta url "_settings"))
