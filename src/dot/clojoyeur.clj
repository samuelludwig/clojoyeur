(ns dot.clojoyeur
  (:require [aero])
  (:gen-class))

(def config-order 
  ["/etc/clojoyeur/config.edn" 
   (str (System/getenv "XDG_CONFIG_HOME") "/clojoyeur/config.edn")])

(defn greet
  "Callable entry point to the application."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (greet {:name (first args)}))
