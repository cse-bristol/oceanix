(ns oceanix.main
  (:require [oceanix.ops :as ops]
            [oceanix.doctl :as dc]
            [oceanix.proc :as proc]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]

            [clojure.set :as set]))

(declare build deploy destroy create-image)

(defn usage [& msg]
  (binding [*out* *err*]
    (println (string/join "\n" msg))
    (System/exit 1)))

(defn real-main [args]
  (let [[c & args] args]
    (case c
      "build"
      (build args)

      "provision"
      (deploy args :only-provision true)
      
      "deploy"
      (deploy args)

      "plan"
      (deploy args :dry-run true)

      "destroy"
      (destroy args)

      "create-image"
      (create-image args)

      (usage "usage: oceanix [--print-stack-trace] build | provision | plan | deploy | destroy | create-image"))))

(defn main [args]
  (let [[pst args] (if (= (first args) "--print-stack-trace")
                     [true (rest args)]
                     [false args])]
    (if pst
      (real-main args)
      (try
        (real-main args)
        (catch Exception e
          (println "Exception: " (ex-message e))
          (doseq [[k v] (ex-data e)]
            (println k v))
          (println "   oceanix --print-stack-trace ... to get cause"))))))

(defn build [args]
  (cond
    (< (count args) 1)
    (usage "usage: oceanix build <network.nix> [<tag-name>]
Note that omitting <tag-name> will mean a rebuild if you have names in the hosts file")

    (not (.exists (io/file (first args))))
    (usage (str (first args) " not found"))

    :else
    (do (ops/build (first args)
                   (dc/tag-hosts (second args)))
        nil)))

(defn print-plan [plan opts]
  (doseq [a plan]
    (ops/print-action a opts)))

(defn print-price [build-result]
  (let [r2 #(/ (Math/round (* 100.0 ^double %)) 100.0)

        size-list
        (->>
         (for [size (dc/size-list)] [(:slug size) size])
         (into {}))

        target-sizes
        (frequencies (map :size (vals build-result)))

        rows  (vec
               (for [[size count] target-sizes
                     :let [size-rec (get size-list size)]]
                 {:size size
                  :count count

                  :cpu (* count (:vcpus size-rec))
                  :mem (* count (:memory size-rec))
                  
                  :hourly1 (:price_hourly size-rec)
                  :monthly1 (:price_monthly size-rec)
                  
                  :hourly  (r2 (* count (:price_hourly size-rec)))
                  :monthly (r2 (* count (:price_monthly size-rec)))
                  }))

        rows
        (cond-> rows
          (> (count rows) 1)
          (conj (-> (reduce
                     #(merge-with + %1 %2)
                     (map
                      #(select-keys
                        % [:hourly :monthly :count :cpu :mem])
                      rows))
                    (assoc :name "TOTAL"))))]
    (with-out-str
      (pprint/print-table
       [:size :cpu :mem :hourly1 :monthly1 :hourly :monthly]
       rows))))

(defn confirm? [& {:keys [response] :or {response "yes"}}]
  (print "Enter" response "to continue: ")
  (flush)
  (= (read-line) response))

(defn deploy [args & {:keys [dry-run] :as opts}]
  (let [{:keys [options arguments errors summary]}
        (parse-opts
         args
         [[nil "--threads N" "How many operations to do in parallel"
           :default 4 :parse-fn #(Integer/parseInt %)]
          [nil "--force" "Whether to ask or just go ahead and do it"]
          ["-h" "--help"]])
        opts (merge opts options)
        args arguments

        {:keys [only-provision force]} opts
        ]
    (cond
      (or (:help opts) (not= 2 (count args)) (seq errors))
      (usage (string/join "\n" errors)
             "usage: oceanix <provision | plan | deploy> [flags] <network.nix> <target-tag>"
             summary)
      
      (not (.exists (io/file (first args))))
      (usage (str (first args) " not found"))

      :else
      (let [[network-file tag] args
            existing-machines (dc/tag-hosts tag)
            build-out      (ops/build network-file existing-machines)
            plan           (ops/plan build-out tag)
            price-table    (print-price build-out)
            ]

        (println)
        (println "Plan:")
        (println)
        (print-plan plan opts)
        (println)

        (println "Running cost:")
        (println price-table)
        
        (when (and (not dry-run)
                   (not (some (comp #{:error} :outcome) plan))
                   (or force (confirm?)))
          (let [host-machines    (keep (fn [[k v]] (when (:addHost v) k)) build-out)
                missing-machines (set/difference (set host-machines)
                                                 (set (keys existing-machines)))

                [plan partial-result build-out]
                (if (or (empty? missing-machines) only-provision)
                  [plan [] build-out]
                  (do
                    (println "Provisioning before rebuild due to missing machines in hosts files")
                    (let [result (ops/realize-plan plan (assoc opts :only-provision true))
                          new-machines (dc/tag-hosts tag)
                          new-build (ops/build network-file new-machines)
                          new-plan  (ops/plan new-build tag)]
                      (println "Provisioning result:")
                      (print-plan result opts)

                      [new-plan result new-build])))
                
                extra-result (ops/realize-plan plan opts)
                whole-result (concat partial-result extra-result)
                ]
            (print-plan whole-result opts)
            ))
        ))))

(defn destroy [args]
  (cond
    (not= 1 (count args))
    (usage "usage: oceanix destroy <target-tag>")

    :else
    (let [machines (dc/droplet-list :tag-name (first args))
          plan     (for [machine machines]
                     {:action :delete :target machine
                      :colour-name (proc/colour (:name machine))})]
      (print-plan plan {})
      (when (and (not-empty plan) (confirm?))
        (let [result (ops/realize-plan plan {})]
          (print-plan result {}))))))

(defn create-image [args]
  (let [[network-file machine-name image-file]
        args]
    (cond
      (not= 3 (count args))
      (usage "usage: oceanix create-image <network.nix> <machine name> <image file name>")

      (not (.exists (io/file network-file)))
      (usage (str network-file " not found"))
      
      :else
      (ops/create-image
       network-file machine-name image-file))))

