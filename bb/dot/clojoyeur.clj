(ns dot.clojoyeur
  (:require [aero.core :as aero]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [babashka.http-client :as http]
            [babashka.process :refer [sh]])
  (:gen-class))

(def config-order
  ["/etc/clojoyeur/config.edn"
   (str (System/getenv "XDG_CONFIG_HOME") "/clojoyeur/config.edn")])

(defn read-config
  [path & aero-opts]
  (when (fs/exists? path) 
    (aero/read-config (io/resource path) (or aero-opts {}))))

(comment
  (require '[clojure.repl :as r])
  (r/source aero/read-config))

(apply merge (->> config-order 
                  (map #(read-config %))
                  (cons {})))

(def clojars-feed-url "http://clojars.org/repo/feed.clj.gz")

(def clojar-feed-destination "/tmp/feed.clj.gz")

(io/copy
  (:body (http/get clojars-feed-url {:as :stream}))
  (io/file clojar-feed-destination))

; (defn gunzip [fname]
;   (with-open [infile (java.util.zip.GZIPInputStream. (io/input-stream fname))]
;     (slurp infile)))

(babashka.process/sh (str "gunzip " clojar-feed-destination))
