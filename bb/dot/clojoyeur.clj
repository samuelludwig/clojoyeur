(ns dot.clojoyeur
  (:require [aero.core :as aero]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [babashka.http-client :as http]
            [babashka.process :refer [sh]]
            [pod.huahaiy.datalevin :as d]
            [taoensso.timbre :as log])
  (:gen-class))

; (require '[babashka.pods :as pods])
; (pods/load-pod 'huahaiy/datalevin "0.7.2")
; (require '[pod.datalevin.core :as d])

(log/merge-config!
  {:appenders {:spit (log/spit-appender {:fname "/tmp/cloyeur.log"})}})

(defn read-env
  ([env-name default]
   (or (System/getenv env-name) default))
  ([env-name]
   (System/getenv env-name)))

(def config-order
  ["/etc/clojoyeur/config.edn"
   (str 
     (read-env "XDG_CONFIG_HOME" "~/.config") 
     "/clojoyeur/config.edn")])

(def config-defaults
  {:clojars-feed-url "https://clojars.org/repo/feed.clj.gz"
   :clojars-feed-destination "/tmp/feed.clj.gz"
   :data-dir (str (read-env "XDG_DATA_HOME") "/cloyeur")})

(defn read-config
  [path & aero-opts]
  (when (fs/exists? path)
    (aero/read-config (io/resource path) (or aero-opts {}))))

(def config 
  (apply merge
    (->> config-order
         (map #(read-config %))
         (cons config-defaults)))) ; append defaults to start 

(def clojars-feed-url "http://clojars.org/repo/feed.clj.gz")

(def clojars-feed-destination "/tmp/feed.clj.gz")

; (io/copy
;   (:body (http/get clojars-feed-url {:as :stream}))
;   (io/file clojars-feed-destination))
;
; (defn gunzip [fname]
;   (with-open [infile (java.util.zip.GZIPInputStream. (io/input-stream
;   fname))]
;     (slurp infile)))

(defn download-web-file
  [url destination]
  (io/copy (:body (http/get url {:as :stream})) (io/file destination)))

(defn gunzip
  "Runs a bare `gunzip` on the given file, removes the destination file first if
  it already exists."
  [fname]
  (if-not (fs/which "gunzip")
    (do (print (str
                 "gunzip needs to be present on your machine and in your PATH, "
                 "exiting for now..."))
        (System/exit 1))
    (do (fs/delete-if-exists fname) (sh (str "gunzip " fname)))))

(def schema #{:package/version {:db/valueType :db.type/string}
              :scm {:db/valueType :db.type/}})

(defn- new-tmp-path
  "Generates a directory path in /tmp with a randomized unique dirname.
  
  Returns the path."
  []
  (str "/tmp/" (random-uuid)))

(def db-location (str (-> config :data-dir) "/feed"))
(def conn (d/get-conn db-location schema))
;(def last-weeks-data (d/get-conn (new-tmp-path) schema))

(log/info {:db db-location})

(comment
  (d/close conn))
  ;(d/close last-weeks-data))

(comment
  (require '[clojure.repl :as r])
  (require '[clojure.pprint :as pp])
  (r/source aero/read-config)
  (r/source fs/delete-if-exists)
  (apply merge
    (->> config-order
         (map #(read-config %))
         (cons {})))
  (pp/pprint (gunzip clojars-feed-destination))
  (download-web-file clojars-feed-url clojars-feed-destination))
