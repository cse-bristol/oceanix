(ns oceanix.proc
  "Utility to run several processes in parallel, interleaving their outputs
  to the console so we can see what's going on a bit."
  (:require [babashka.process :as bbp]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:const RESET "\033[0m")
(def ^:const BLACK  "\033[0;30m")
(def ^:const RED  "\033[0;31m")
(def ^:const GREEN  "\033[0;32m")
(def ^:const YELLOW  "\033[0;33m")
(def ^:const BLUE  "\033[0;34m")
(def ^:const PURPLE  "\033[0;35m")
(def ^:const CYAN  "\033[0;36m")
(def ^:const WHITE  "\033[0;37m")
(def ^:const BOLD "\033[0;1m")

(def ^:const BLACK_BACKGROUND  "\033[40m")
(def ^:const RED_BACKGROUND  "\033[41m")
(def ^:const GREEN_BACKGROUND  "\033[42m")
(def ^:const YELLOW_BACKGROUND  "\033[43m")
(def ^:const BLUE_BACKGROUND  "\033[44m")
(def ^:const PURPLE_BACKGROUND  "\033[45m")
(def ^:const CYAN_BACKGROUND  "\033[46m")

(def colors [RED GREEN YELLOW BLUE PURPLE CYAN
             BLACK_BACKGROUND RED_BACKGROUND
             GREEN_BACKGROUND YELLOW_BACKGROUND
             BLUE_BACKGROUND PURPLE_BACKGROUND
             CYAN_BACKGROUND])

(defonce id (atom 0))
(def out-lock (Object.))
(def err-lock (Object.))

(def ^:dynamic *sh-env* {})
(def ^:dynamic *identifier* nil)

(defn colour [string]
  (let [id (swap! id inc)]
    (str (nth colors (mod id (count colors))) string RESET)))

(defn sh* [args opts]
  (let [cmd     (first args)
        id      (swap! id inc)
        idstr   (or (and *identifier* (str *identifier* " " id))
                    (str (nth colors (mod id (count colors))) id RESET))
        id      (str idstr " " BOLD cmd RESET)]
    (locking out-lock
      (println id (string/join " " (rest args))))
    (let [tee-out (= :tee (:out opts))
          tee-err (= :tee (:err opts))

          result (bbp/process
                  (vec args)
                  (-> (cond-> opts
                        tee-out (dissoc :out)
                        tee-err (dissoc :err)

                        (not-empty *sh-env*)
                        (update :env #(merge *sh-env* %)))
                      ))
          
          out (when tee-out
                (let [real-out (:out result)
                      out (java.lang.StringBuilder.)]
                  (future
                    (doseq [line (line-seq (io/reader real-out))]
                      (locking out-lock
                        (print id)
                        (println ">" line))
                      (.append out line)
                      (.append out "\n"))
                    (.toString out))))
          
          err  (when tee-err
                 (let [real-err (:err result)
                       err (java.lang.StringBuilder.)]
                   (future
                     (doseq [line (line-seq (io/reader real-err))]
                       (locking err-lock
                         (binding [*out* *err*]
                           (print id)
                           (print "> ")
                           (print RED)
                           (print line)
                           (println RESET)))
                       (.append err line)
                       (.append err "\n"))
                     (.toString err))))
          
          result @result
          result
          (cond-> {:exit (:exit result)
                   :out (:out result)
                   :err (:err result)
                   :cmd (vec args)}
            tee-out (assoc :out @out)
            tee-err (assoc :err @err))
          ]
      (when-not (zero? (:exit result))
        (locking err-lock
          (println id "exit" (:exit result))))
      (when-let [valid-exit-code (:valid-exit-code opts)]
        (when-not (valid-exit-code (:exit result))
          (throw
           (ex-info (str "error running " (string/join " " args))
                    (assoc result
                           :type :exec)))))
      result)))

(defn sh [& args] (sh* args {:out :string :err :tee}))

(defn sh! [& args]
  (:out (sh* args {:out :tee :err :tee :valid-exit-code #{0}})))
