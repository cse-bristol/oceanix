(ns oceanix.proc
  "Utility to run several processes in parallel, interleaving their outputs
  to the console so we can see what's going on a bit."
  (:require [babashka.process :as bbp]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:const RESET "\033[0m")
(def ^:const BLACK  "\033[0;30m");   // BLACK
(def ^:const RED  "\033[0;31m");     // RED
(def ^:const GREEN  "\033[0;32m");   // GREEN
(def ^:const YELLOW  "\033[0;33m");  // YELLOW
(def ^:const BLUE  "\033[0;34m");    // BLUE
(def ^:const PURPLE  "\033[0;35m");  // PURPLE
(def ^:const CYAN  "\033[0;36m");    // CYAN
(def ^:const WHITE  "\033[0;37m");   // WHITE

(def colors [RED GREEN YELLOW BLUE PURPLE CYAN WHITE])

(defonce id (atom 0))
(def out-lock (Object.))
(def err-lock (Object.))

(def ^:dynamic *sh-env* {})

(defn sh* [args opts]
  (let [cmd     (first args)
        id      (swap! id inc)
        id      (str (nth colors (mod id (count colors)))
                     id " " cmd RESET)]
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
          ]
      (when-not (zero? (:exit result))
        (locking err-lock
          (println id "exit" (:exit result))))
      (cond-> {:exit (:exit result)
               :out (:out result)
               :err (:err result)
               :cmd (vec args)}
        tee-out (assoc :out @out)
        tee-err (assoc :err @err)))))

(defn sh [& args] (sh* args {:out :string :err :tee}))

(defn sh! [& args]
  (let [result (sh* args {:out :tee :err :tee})]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "error running " (first args))
                      {:type :exec
                       :command (vec args)
                       :err (:err result)
                       :out (:out result)})))
    (:out result)))
