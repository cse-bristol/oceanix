(ns oceanix.doctl
  "Wrapper around the doctl command"
  (:require [oceanix.proc :refer [sh* sh! our-env]]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.io :as io]))

(defn doctl* [args]
  (when-not (contains? our-env "DIGITALOCEAN_ACCESS_TOKEN")
    (throw (ex-info "No access token! You need to export DIGITALOCEAN_ACCESS_TOKEN for doctl to work" {})))
  
  (let [{:keys [exit out err] :as x}
        (sh* (concat ["doctl"] args ["-o" "json"])
             {:err :tee :out :string})]
    (if (zero? exit)
      (when-not (string/blank? out)
        (json/parse-string out true))
      (throw (ex-info
              "error in doctl"
              {:type :exec
               :command (vec (concat ["doctl"] args))
               :err
               (-> out
                   (json/parse-string true)
                   (:errors)
                   (->> (map :detail)
                        (string/join "\n")))})))))

(defn doctl [& args] (doctl* args))

(defn droplet-list [& {:keys [tag-name region]}]
  (doctl* (cond-> ["compute" "droplet" "list"]
            tag-name (-> (conj "--tag-name")
                         (conj tag-name))
            region (-> (conj "--region")
                       (conj region)))))

(defn droplet-delete [id]
  (doctl "compute" "droplet" "delete" "--force" id))

(defn image-id
  "Seems like custom images have no slug?"
  [region name]
  (->> (doctl "compute" "image" "list")
       (filter
        (fn [x]
          (and (= (:name x) name)
               (contains? (set (:regions x)) region))))
       (first)
       (:id)))

(defn ssh-key-list [] (doctl "compute" "ssh-key" "list"))

(defn ssh-key-ensure [name public-key]
  (let [keys (ssh-key-list)
        key  (->> keys
                  (filter (comp #{public-key} :public_key))
                  (first))]
    (if key
      (:id key)

      (-> (doctl "compute" "ssh-key" "create"
                 name "--public-key" public-key)
          (first)
          (:id)))))

(defn droplet-create [name ssh-key &
                      {:keys [region size image wait tag]
                       :or {region "lon1"
                            size "1gb"
                            image "nixos-20.09"
                            wait true}}]
  (first
   (doctl* (cond->
               ["compute" "droplet" "create"
                name
                "--region" region
                "--size" size
                "--image" image
                "--ssh-keys" ssh-key]
             tag
             (-> (conj "--tag-name")
                 (conj tag))
             
             wait
             (conj "--wait")))))

(defn public-ip [droplet]
  (->> (:networks droplet)
       (:v4)
       (keep
        (fn [{:keys [type ip_address]}]
          (when (= type "public") ip_address)))
       (first)))

(defn size-list []
  (doctl "compute" "size" "list"))

(defn tag-hosts
  "Return a map from name to IP for hosts in a tag.
  Duplicate names will be ignored."
  [tag]
  (if (string/blank? tag)
    {}
    (->> (for [droplet (droplet-list :tag-name tag)
               :let [ip (public-ip droplet)]
               :when ip]
           [(:name droplet) ip])
         (into {}))))

(defn s3cmd! [args
              {:keys [access-key secret-key region]
               :or
               {access-key (our-env "DIGITALOCEAN_SPACES_ACCESS_KEY")
                secret-key (our-env "DIGITALOCEAN_SPACES_SECRET_KEY")
                region "ams3"}}]
  (let [s3host (str region ".digitaloceanspaces.com")]
    (:out
     (sh* (concat
           ["s3cmd"
            "--access_key" access-key
            "--secret_key" secret-key
            "--host" s3host
            "--host-bucket" (str "%(bucket)s." s3host)]
           args)
          {:out :string :err :tee :valid-exit-code #{0}
           :hide-args {access-key (str "$DIGITALOCEAN_SPACES_ACCESS_KEY")
                       secret-key (str "$DIGITALOCEAN_SPACES_SECRET_KEY")}
           }))))

(defn s3-create-bucket [bucket opts]
  (let [existing-buckets
        (-> (s3cmd! ["ls"] opts)
            (string/split-lines)
            (->> (map #(last (string/split % #" +"))))
            (set))]
    (when-not (contains? existing-buckets bucket)
      (s3cmd! ["mb" bucket] opts))))

(defn s3-upload-file
  [file target opts]
  (-> (s3cmd! ["put" file target "--acl-public"]
              opts)
      (string/split-lines)
      (last)
      (->> (re-find #"(https?://.+)"))
      (last)))

(defn create-image [file image-name
                    {:keys [spaces-bucket description region tag]
                     :or {spaces-bucket "s3://disk-images"}
                     :as opts}]
  
  (let [spaces-bucket (if (.startsWith spaces-bucket "s3://")
                        spaces-bucket
                        (str "s3://" spaces-bucket))

        target-name (str spaces-bucket "/" (str (java.util.UUID/randomUUID) "-" image-name ".qcow2.bz2"))
        s3-opts (assoc opts :region (:spaces-region opts))
        _       (s3-create-bucket spaces-bucket s3-opts)
        s3-url (s3-upload-file
                (.getCanonicalPath file)
                target-name s3-opts)

        image-id (-> (doctl*
                      (cond->
                          ["compute" "image" "create" image-name
                           "--region" region
                           "--image-url" s3-url
                           "--image-distribution" "nixos"]
                        (not (string/blank? description))
                        (-> (conj "--image-description")
                            (conj description))

                        (not (string/blank? tag))
                        (-> (conj "--tag-names") (conj tag))))
                     (first)
                     (:id))
        ]
    (loop []
      (let [status
            (-> (doctl "compute" "image" "get" image-id)
                (first)
                (:status))]
        (when-not (= "available" status)
          (println "Waiting for image [10s]..." status)
          (Thread/sleep 10000)
          (recur))))
    (s3cmd! ["rm" target-name] s3-opts)))

(defn find-or-create-project
  "Get project ID from project name. If you have multiple projects with
  same name, gets the min ID."
  [name]
  (-> (->> (doctl "projects" "list")
           (filter (comp #{name} :name))
           (sort-by :id)
           (first)
           (:id))
      (or (-> (doctl "projects" "create"
                  "--name" name
                  "--purpose" "Created by a deployment")
              (first)
              (:id)))))

(defn assign-to-project [name & {:keys [droplet-ids]}]
  (let [project-id (find-or-create-project name)]
    (doctl*
     (into ["projects" "resources" "assign" project-id]
           (for [droplet droplet-ids]
             (str "--resource=do:droplet:" droplet))))))



