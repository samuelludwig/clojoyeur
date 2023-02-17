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

(log/merge-config! {:appenders {:spit (log/spit-appender
                                        {:fname "/tmp/cloyeur.log"})}})

(defn read-env
  ([env-name default] (or (System/getenv env-name) default))
  ([env-name] (System/getenv env-name)))

(def config-order
  ["/etc/clojoyeur/config.edn"
   (str (read-env "XDG_CONFIG_HOME" (fs/expand-home "~/.config"))
        "/clojoyeur/config.edn")])

(def config-defaults
  {:clojars-feed-url "https://clojars.org/repo/feed.clj.gz",
   :clojars-feed-destination "/tmp/feed.clj.gz",
   :data-dir         (str (read-env "XDG_DATA_HOME"
                                    (fs/expand-home "~/.local/share"))
                          "/cloyeur")})

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

(def schema
  {:package/versions       {:db/cardinality :db.cardinality/many,
                            :db/valueType   :db.type/string},
   :package.scm/connection {:db/valueType :db.type/string},
   :package.scm/developer-connection {:db/valueType :db.type/string},
   :package.scm/tag        {:db/valueType :db.type/string},
   :package.scm/url        {:db/valueType :db.type/string},
   :package/scm            {:db/valueType  :db.type/tuple,
                            :db/tupleAttrs [:package.scm/connection
                                            :package.scm/developer-connection
                                            :package.scm/url :package.scm/tag]},
   :package/description    {:db/valueType :db.type/string,
                            :db/fulltext true},
   :package/artifact-id    {:db/valueType :db.type/string
                            :db/fulltext true},
   :package/group-id       {:db/valueType :db.type/string,
                            :db/fulltext true},
   :package/id             {:db/valueType  :db.type/tuple,
                            :db/tupleAttrs [:package/group-id
                                            :package/artifact-id],
                            :db/unique     :db.unique/identity}})

(defn feed-map->package-map
  [{:keys [versions description artifact-id group-id scm], :as _feed-map}]
  (let [{:keys [connection developer-connection url tag]} scm]
    {:package/versions       versions,
     :package/description    description,
     :package.scm/connection connection,
     :package.scm/developer-connection developer-connection,
     :package.scm/url        url,
     :package.scm/tag        tag,
     :package/artifact-id    artifact-id,
     :package/group-id       group-id}))

(def feed-map-str->package-map
  (comp feed-map->package-map clojure.edn/read-string))

(def test-map
  {:versions    ["1.1.0" "1.1.0-SNAPSHOT"],
   :scm         {:connection
                   "scm:git:git://github.com/technomancy/swank-clojure.git",
                 :developer-connection
                   "scm:git:ssh://git@github.com/technomancy/swank-clojure.git",
                 :tag "da6cb50944ba95940559a249c9659f71747312fb",
                 :url "http://github.com/technomancy/swank-clojure"},
   :description "Swank server connecting Clojure to Emacs SLIME",
   :artifact-id "swank-clojure",
   :group-id    "swank-clojure"})

;(feed-map->package-map test-map)

(defn process-file-by-lines
  "Process file reading it line-by-line.
  Source: https://stackoverflow.com/a/25950711"
  ([file] (process-file-by-lines file identity))
  ([file process-fn] (process-file-by-lines file process-fn println))
  ([file process-fn output-fn]
   (with-open [rdr (clojure.java.io/reader file)]
     (doseq [line (line-seq rdr)] (output-fn (process-fn line))))))

;(process-file-by-lines "./resources/testfeed.clj" feed-map-str->package-map
;pp/pprint)

(defn insert-package [conn package-map] (d/transact! conn [package-map]))

(defn feed-inserter [conn] (partial insert-package conn))

(defn- new-tmp-path
  "Generates a directory path in /tmp with a randomized unique dirname.
  
  Returns the path."
  []
  (str "/tmp/" (random-uuid)))

(def db-location
  (str (-> config
           :data-dir)
       "/feed"))

(defn clean-db
  []
  (fs/delete-tree db-location))
  ;"/home/dot/.local/share/cloyeur/feed")
(def conn (d/get-conn db-location schema))
(comment
  (d/close conn)
  (clean-db))

(defn update-db [conn]
  (process-file-by-lines "./resources/testfeed.clj"
                         feed-map-str->package-map
                         #(insert-package conn %)))

(comment
  (d/q '[:find (pull ?e [*]) :in $ ?group-id :where [?e :package/id ?group-id]]
       (d/db conn)
       ["swank-clojure" "swank-clojure"])

  ;; fulltext
  (d/q '[:find (pull ?e [*]) 
         :in $ ?q 
         :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn)
       "technomancy")

  (d/q '[:find (pull ?e [*]) 
         :in $
         :where [?e _ _]]
       (d/db conn))

  (d/q '[:find ?scm :in $ ?group-id :where [?e :package/id ?group-id]
         [?e :package/scm ?scm]]
       (d/db conn)
       ["swank-clojure" "swank-clojure"]))

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
