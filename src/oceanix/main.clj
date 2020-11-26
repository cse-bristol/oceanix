(ns oceanix.main
  (:require [oceanix.ops :as ops]
            [oceanix.doctl :as dc]
            [oceanix.proc :as proc :refer [our-env]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]

            [clojure.set :as set]))

(declare deploy destroy create-images create-base-image)

(defn exit [status & lines]
  (doseq [l lines]
    (println l))
  (System/exit status))

(defn or-env [opts key]
  (or (get opts key)
      (let [env-var (str "DEPLOY_"
                         (-> (name key)
                             (string/upper-case)
                             (string/replace "-" "_")))]
        (or (get our-env env-var)
            (exit 1 (str "--" (name key) " is required (or set " env-var ")"))))))

(declare commands)

(defn main [[command-name & arguments]]
  (let [command (get commands (keyword command-name))]
    (if command
      (let [{:keys [options arguments errors summary]}
            (parse-opts arguments
                        (conj (:opts command) ["-h" "--help"]))]
        (if (or (:help options) (seq errors))
          (apply
           exit (if (:help options) 0 1)
           (str "usage: oceanix " command-name " [options]")
           (:help command)
           summary errors)
          ((:run command) options arguments)))
      (exit 1 (str "usage: oceanix "
                   (string/join " | " (sort (map name (keys commands))))
                   " [options]\n"

                   (string/join
                    "\n"
                    (for [[k v] (sort-by first commands)]
                      (str "\t" (name k) ": " (:help v))))
                   )))))

(def commands
  (let [network-file
        ["-n" "--network FILE" "A nix file defining a network"
         :validate [#(.exists (io/file %)) "Network file not found"]]

        tag
        ["-t" "--tag TAG" "A tag, used on digitalocean to identify a deployment"]

        force
        ["-f" "--force" "Make changes to digitalocean without asking for confirmation"]

        threads
        [nil "--threads N" "How many machines to deploy in parallel"
         :default 4 :parse-fn #(Integer/parseInt %)
         :validate [pos? "Must have a positive number of threads"]]

        upload-options
        [["-r" "--region R" "What region to create the image in"
          :default "lon1"]
         [nil "--spaces-region R" "Uploads go via DO spaces in this region"
          :default "ams3"]
         [nil "--spaces-bucket B" "Uploads go via DO spaces in this bucket"]]
        ]
    
    {:build
     {:help "Build the system roots for a deployment"
      :opts [network-file tag]
      
      :run
      (fn [{:keys [tag] :as opts} _]
        (let [network (or-env opts :network)
              tag     (or tag (our-env "DEPLOY_TAG"))]
          (ops/build network (if tag (dc/tag-hosts tag) {}))))
      }

     :provision
     {:help "Create / destroy machines for a deployment"
      :opts [network-file tag force threads]
      :run
      (fn [opts _]
        (let [network (or-env opts :network)
              tag     (or-env opts :tag)]
          (deploy network tag (assoc opts :only-provision true))))}
     
     :deploy
     {:help "Provision and then deploy a network"
      :opts [network-file tag force threads]
      :run
      (fn [opts _]
        (let [network (or-env opts :network)
              tag     (or-env opts :tag)]
          (deploy network tag (assoc opts :only-provision false))))}
     
     :destroy
     {:help "Destroy all the machines in a tag"
      :opts [tag]
      :run
      (fn [{:keys [tag]} _]
        (or tag (exit 1 "--tag is required"))
        (destroy tag))
      }
     
     :image
     {:help "Create images for machines in a network"
      :opts (into [network-file
                   ["-u" "--upload" "Whether to upload the images"]]
                  upload-options)
      :run
      (fn [{:keys [upload tag] :as opts} _]
        (let [network       (or-env opts :network)
              tag           (or tag (our-env "DEPLOY_TAG"))
              region        (and upload (or-env opts :region))
              spaces-region (and upload (or-env opts :spaces-region))
              spaces-bucket (and upload (or-env opts :spaces-bucket))]
          (create-images
           network tag
           (assoc opts
                  :region region
                  :spaces-region spaces-region
                  :spaces-bucket spaces-bucket))))
      }
     
     :base-image
     {:help "Create a base nixos image with nothing in it"
      :opts (into [["-k" "--ssh-key PUBKEY" "A public key"]
                   ["-u" "--upload NAME" "What name to upload the image as"]]
                  upload-options)
      :run
      (fn [{:keys [upload] :as opts} _]
        (let [ssh-key       (or-env opts :ssh-key)
              region        (and upload (or-env opts :region))
              spaces-region (and upload (or-env opts :spaces-region))
              spaces-bucket (and upload (or-env opts :spaces-bucket))]
          (create-base-image
           (assoc opts
                  :ssh-key ssh-key
                  :region region
                  :spaces-region spaces-region
                  :spaces-bucket spaces-bucket
                  :upload upload))
          
          ))}}))

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

(defn deploy [network-file tag {:keys [only-provision dry-run force] :as opts}]
  (let [existing-machines (dc/tag-hosts tag)
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
    ))

(defn destroy [tag]
  (let [machines (dc/droplet-list :tag-name tag)
        plan     (for [machine machines]
                   {:action :delete :target machine
                    :colour-name (proc/colour (:name machine))})]
    (print-plan plan {})
    (when (and (not-empty plan) (confirm?))
      (let [result (ops/realize-plan plan {})]
        (print-plan result {})))))

(defn create-base-image [{:keys [ssh-key upload] :as options}]
  (let [image (ops/create-base-image ssh-key)]
    (if upload
      (dc/create-image
       (io/file image "nixos.qcow.bz2")
       upload
       options)
      
      (println image))))

(defn create-images [network tag {:keys [upload] :as options}]
  (let [tag-hosts (if tag (dc/tag-hosts tag) {})
        images (ops/create-network-images network tag-hosts)]
    (if upload
      (doseq [[image-name image-path] images]
        (dc/create-image
         (io/file image-path "nixos.qcow.bz2")
         image-name
         options))
      (doseq [[image-name path] images]
        (println image-name path)))))
