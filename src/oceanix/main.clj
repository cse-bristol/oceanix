(ns oceanix.main
  (:require [oceanix.ops :as ops]
            [oceanix.doctl :as dc]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            ))

(declare build deploy destroy create-image)

(defn usage [msg]
  (binding [*out* *err*]
    (println msg)
    (System/exit 1)))

(defn main [args]
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

      (usage "usage: oceanix build | provision | plan | deploy | destroy | create-image"))))

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
                    (assoc :name "TOTAL"))))
        ]

    (pprint/print-table
     [:size :cpu :mem :hourly1 :monthly1 :hourly :monthly]
     rows)))

(defn deploy [args & {:keys [dry-run] :as opts}]
  (cond
    (not= 2 (count args))
    (usage "usage: oceanix <provision | plan | deploy> <network.nix> <target-tag>")

    (not (.exists (io/file (first args))))
    (usage (str (first args) " not found"))

    :else
    (let [[network-file tag] args
          existing-machines (dc/tag-hosts tag)
          build-result      (ops/build network-file existing-machines)
          plan              (ops/plan build-result tag)]
      (println "Price of deployment:")
      (print-price build-result)
      (println)
      (println "Plan:")
      (print-plan plan opts)
      (when-not dry-run
        (let [result (ops/realize-plan plan opts)]
          (print-plan result opts))

        (let [host-machines  (keep (fn [[k v]] (when (:host v) k))
                                   build-result)]
          (when (seq host-machines)
            (let [existing-machines (select-keys existing-machines
                                                 host-machines)
                  new-machines (select-keys (dc/tag-hosts tag)
                                            host-machines)]
              (when (not= existing-machines new-machines)
                (println "Hosts have changed, so you should deploy again!")))))))))

(defn destroy [args]
  (cond
    (not= 1 (count args))
    (usage "usage: oceanix destroy <target-tag>")

    :else
    (let [machines (dc/droplet-list :tag-name (first args))
          plan     (for [machine machines]
                     {:action :delete :target machine})]
      (print-plan plan {})
      (let [result (ops/realize-plan plan {})]
        (print-plan result {})))))

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

