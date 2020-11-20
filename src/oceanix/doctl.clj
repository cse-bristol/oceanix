(ns oceanix.doctl
  "Wrapper around the doctl command"
  (:require [oceanix.proc :refer [sh*]]
            [cheshire.core :as json]
            [clojure.string :as string]))

(defn doctl* [args]
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
