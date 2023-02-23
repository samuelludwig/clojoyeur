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

(def config-home (read-env "XDG_CONFIG_HOME" (fs/expand-home "~/.config")))

(def data-home (read-env "XDG_DATA_HOME" (fs/expand-home "~/.local/share")))

(def config-order
  ["/etc/clojoyeur/config.edn" (str config-home "/clojoyeur/config.edn")])

(def config-defaults
  {:clojars-feed-url "https://clojars.org/repo/feed.clj.gz",
   :clojars-feed-destination "/tmp/feed.clj.gz",
   :data-dir         (str data-home "/cloyeur")})

(defn read-config
  [path & aero-opts]
  (when (fs/exists? path)
    (aero/read-config (io/resource path) (or aero-opts {}))))

(def config
  (apply merge
    (->> config-order
         (map #(read-config %))
         (cons config-defaults)))) ; append defaults to start

(def clojars-feed-url
  (-> config
      :clojars-feed-url))

(def clojars-feed-destination
  (-> config
      :clojars-feed-destination))

(defn copy
  [uri file]
  (with-open [in  (io/input-stream uri)
              out (io/output-stream file)]
    (io/copy in out)))

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
  (try [:ok
        (io/copy (:body (http/get url {:as :stream})) (io/file destination))]
       (catch Exception _
         [:error (str "Download URL " url " could not be resolved.")])))
  ; (let [input (slurp url)
  ;       file (io/file destination)]
  ;   (io/copy input file)))

(defn remove-from-end
  "Remove last appearance of a given pattern in a string.
  
  From https://stackoverflow.com/questions/13705677/clojure-remove-last-entrance-of-pattern-in-string"
  [s end]
  (if (clojure.string/ends-with? s end) (subs s 0 (- (count s) (count end))) s))

(defn gunzip
  "Runs a bare `gunzip` on the given file, removes the destination file first if
  it already exists."
  [fname]
  (if-not (fs/which "gunzip")
    (do (print (str
                 "gunzip needs to be present on your machine and in your PATH, "
                 "exiting for now..."))
        (System/exit 1))
    (do (fs/delete-if-exists (remove-from-end fname ".gz"))
        (sh (str "gunzip " fname)))))

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
   :package/description    {:db/valueType :db.type/string, :db/fulltext true},
   :package/artifact-id    {:db/valueType :db.type/string, :db/fulltext true},
   :package/group-id       {:db/valueType :db.type/string, :db/fulltext true},
   :package/id             {:db/valueType  :db.type/tuple,
                            :db/tupleAttrs [:package/group-id
                                            :package/artifact-id],
                            :db/unique     :db.unique/identity}})

(defn feed-map->package-map
  [{:keys [versions description artifact-id group-id scm], :as _feed-map}]
  (let [{:keys [connection developer-connection url tag]} scm]
    {:package/versions       versions,
     :package/description    (or description ""),
     :package.scm/connection (or connection ""),
     :package.scm/developer-connection (or developer-connection ""),
     :package.scm/url        (or url ""),
     :package.scm/tag        (or tag ""),
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

(defn clean-db [] (fs/delete-tree db-location))

  ;"/home/dot/.local/share/cloyeur/feed")
(def conn (d/get-conn db-location schema))
(comment
  (d/close conn)
  (clean-db))

#_(defn insert-packages-into-db
    [conn packages-file]
    (process-file-by-lines packages-file
                           feed-map-str->package-map
                           #(insert-package conn %)))

(defn insert-packages-into-db
  [conn packages-file]
  (with-open [rdr (io/reader packages-file)]
    (let [packages (pmap feed-map-str->package-map (line-seq rdr))]
      (d/transact! conn packages))))
    

(defn- error? [x] (= :error x))

;
; (defn remove-from-end
;   "Remove last appearance of a given pattern in a string.
;   
;   From
;   https://stackoverflow.com/questions/13705677/clojure-remove-last-entrance-of-pattern-in-string"
;   [s end]
;   (if (.endsWith s end) (.substring s 0 (- (count s) (count end))) s))
;
;(remove-from-end clojars-feed-destination ".gz")

(defn update-db
  [conn
   {:keys [clojars-feed-url clojars-feed-destination data-dir], :as _config}]
  (let [[dl-status dl-msg] (download-web-file clojars-feed-url
                                              clojars-feed-destination)]
    (if (error? dl-status)
      (do (println dl-msg) (System/exit 1))
      (do (gunzip clojars-feed-destination)
          (clean-db)
          (insert-packages-into-db conn
                                   (remove-from-end clojars-feed-destination
                                                    ".gz"))))))

(defn search-for
  [phrase]
  (d/q '[:find (pull ?e [*]) :in $ ?q :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn)
       phrase))

(update-db conn config) ;; Need to make this faster, just pins one CPU for eternity

(comment
  (d/q '[:find (pull ?e [*]) :in $ ?group-id :where [?e :package/id ?group-id]]
       (d/db conn)
       ["swank-clojure" "swank-clojure"])
  ;; fulltext search
  (d/q '[:find (pull ?e [*]) :in $ ?q :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn)
       "swank clojure")
  (search-for "swank")
  ;; Show me everything
  (d/q '[:find (pull ?e [*]) :in $ :where [?e _ _]] (d/db conn))
  ;; Show me the scm data for the following package
  (d/q '[:find ?scm :in $ ?id :where [?e :package/id ?id]
         [?e :package/scm ?scm]]
       (d/db conn)
       ["swank-clojure" "swank-clojure"]))

(log/info {:db db-location})

(comment
  (d/close conn))

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
