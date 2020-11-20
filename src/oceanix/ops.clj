(ns oceanix.ops
  (:require [oceanix.doctl :as dc]
            [oceanix.proc :refer [sh!] :as proc]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.core.async :as async]))

(defn support-file [name]
  (-> (io/resource (str "oceanix/" name))
      (io/file)
      (.getCanonicalPath)))

(def network.nix (support-file "nix/network.nix"))

(defn keywordize-keys [m]
  (reduce-kv
   (fn [a k v] (assoc a (keyword k) v))
   {} m))

(defn build
  "Evaluate the network in `network-file`, and read the result.
  This result defines the hosts to create & deploy.

  `tag-hosts` should map host names to ip addresses; this is naturally
  a bit circular, since a provisioning operation is needed to know ips.

  Rather than solving this properly, we just rerun the build / deploy
  until this is stable.
  "
  [network-file tag-hosts]
  (let [tag-hosts (json/generate-string (or tag-hosts {}))

        json (-> (io/file network-file)
                 (.getCanonicalPath)
                 (->> (sh! "nix-build"
                           network.nix
                           "--argstr" "hosts" tag-hosts
                           "--argstr" "network-file"))
                 (string/trim)
                 (slurp)
                 (json/parse-string))]
    (reduce-kv
     (fn [a k v]
       (let [v (keywordize-keys v)
             copies (:copies v 1)]
         (if (> copies 1)
           (->> (for [i (range copies)] [(str k "-" (inc i)) v])
                (into {})
                (merge a))
           (assoc a k v))))
     {} json)))

(defn plan [build-result tag]
  (let [defs (vals build-result)

        ssh-keys ; we don't build these in parallel in case we kill
                 ; the API. TODO UGLY: we create the key whilst
                 ; planning, which is wrong.
        (->> (map :ssh-key defs)
             (set)
             (reduce
              (fn [a key]
                (assoc a key (delay (dc/ssh-key-ensure tag key))))
              {}))
        
        images ; we do build these in parallel since we are only
               ; quering
        (->> (map (juxt :region :image) defs)
             (set)
             (pmap (fn [[region image]]
                     [[region image]
                      (dc/image-id region image)]))
             (into {}))

        ;; augment machines with image ids and key ids
        ;; and the tag to deploy into
        targets
        (->> (for [[name d] build-result]
               (let [image-id
                     (if (integer? (:image d))
                       (:image d)
                       (get images [(:region d) (:image d)]))]
                 [name
                  (cond->
                      (assoc
                       d
                       :name name
                       :tag tag
                       :ssh-key-id (get ssh-keys (:sshKey d))
                       :image-id image-id)
                    (nil? image-id)
                    (assoc :outcome :error
                           :error (str "image " (:image d) "not found")))
                  ]))
             (into {}))

        existing
        (->> (dc/droplet-list :tag-name tag)
             (group-by :name))

        all-names (set/union
                   (set (keys existing))
                   (set (keys targets)))
        ]

    ;; construct THE PLAN
    (loop [all-names all-names
           actions  nil]
      (if (empty? all-names)
        actions

        (let [[machine-name & all-names] all-names
              target-conf (get targets machine-name)
              [existing & extra] (get existing machine-name)

              action
              (cond
                (and target-conf existing)
                {:action :deploy
                 :source target-conf
                 :target existing}

                (and (not target-conf) existing)
                {:action :delete :target existing}

                (and target-conf (not existing))
                {:action :create :source target-conf})
              
              actions (conj actions action)
              ]
          (recur
           all-names
           (if (seq extra)
             (reduce
              (fn [a d] (conj a {:action :delete :target d}))
              actions
              extra)
             actions)))))))

(def ^:const system-profile "/nix/var/nix/profiles/system")

(defmulti realize-action  (fn [a _] (:action a)))
(defmethod realize-action :deploy
  [{:keys [source target]} {:keys [only-provision]}]
  (when-not only-provision
    (let [system (:system source)
          host (str "root@" (dc/public-ip target))]
      ;; TODO really disable key checks - urgh
      (binding [proc/*sh-env* (merge
                               (into {} (System/getenv))
                               {"NIX_SSHOPTS" "-T -oStrictHostKeyChecking=no"})]
        (sh! "nix-copy-closure" "--to" host "-s" system))
      (sh! "ssh" "-T" "-oStrictHostKeyChecking=no" host "nix-env" "--profile" system-profile "--set" system)
      (sh! "ssh" "-T" "-oStrictHostKeyChecking=no" host (str system "/activate")))))

(defmethod realize-action :create
  [{:keys [source]} o]
  (let [target
        (dc/droplet-create
         (:name source)
         (let [id (:ssh-key-id source)
               id (if (instance? clojure.lang.Delay id)
                    (deref id) id)]
           (when-not id
             (throw (ex-info "ssh key id missing"
                             {:key (:sshKey source)})))
           id)
         
         :image (:image-id source)
         :region (:region source)
         :size (:size source)
         :tag (:tag source)
         )]
    (realize-action
     {:action :deploy :source source :target target}
     o)))

(defmethod realize-action :delete
  [{:keys [target]} _]
  (dc/droplet-delete (:id target)))

(defn realize-action* [a o]
  (try
    (realize-action a o)
    (assoc a :outcome :success)
    (catch Exception e
      (assoc a
             :outcome :failure
             :error (ex-message e)
             :error-data (ex-data e)
             ))))

(defn realize-plan [plan {:keys [threads only-provision]
                          :or {threads 1 only-provision false}
                          :as opts}]
  (if (= 1 threads)
    (doall (map #(realize-action* % opts) plan))
    (let [in  (async/chan)
          out (async/chan)]
      (async/pipeline-async
       threads
       out
       (fn [a o]
         (async/thread
           (async/put! o (realize-action* a opts))
           (async/close! o)))
       in)

      (async/thread
        (doseq [a plan] (async/put! in a))
        (async/close! in))

      (loop [all-out nil]
        (when-let [r (async/<! out)]
          (conj all-out r)
          (recur all-out))))))

(defmulti print-action (fn [a o] (:action a)))

(let [CREATE   (str proc/CYAN "CREATE" proc/RESET)
      UPDATE   (str proc/BLUE "UPDATE" proc/RESET)
      DESTROY  (str proc/YELLOW "DESTROY" proc/RESET)
      SUCCESS  (str proc/GREEN "OK" proc/RESET)
      ERROR    (str proc/RED "FAILED" proc/RESET)

      outcome  (fn [a]
                 (case (:outcome a)
                     :success
                     (println "  " SUCCESS)

                     :failure
                     (do (println "  " ERROR)
                         (println (:error a))
                         (doseq [[k v] (:error-data a)]
                           (println k v)))

                     nil
                     (println)

                     (do (println "  " ERROR)
                         (println "  Unknown outcome: "
                                  (:outcome a)))))
      ]
  (defmethod print-action :create [{:keys [source] :as a} o]
    (print (if (:only-provision o)
             CREATE (str CREATE "+" UPDATE)) 
           (:name source) (:size source))
    (outcome a))

  (defmethod print-action :delete [{:keys [target] :as a} o]
    (print DESTROY (:name target) (:id target))
    (outcome a))

  (defmethod print-action :deploy [{:keys [target] :as a} o]
    (when-not (:only-provision o)
      (print UPDATE (:name target) (dc/public-ip target))
      (outcome a)
      )))

(defn create-image [network-file machine target]
  (sh! "nix-build" network.nix
       "--argstr" "network-file"
       (.getCanonicalPath (io/file network-file))
       "--argstr" "create-image" machine
       "--out-link" target))
