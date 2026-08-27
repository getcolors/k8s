(ns io.github.getcolors.k8s.validate
  "Credential-free kubeadm/DigitalOcean desired-state validation."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]))

(def providers
  {:provider-compute
   {"digitalocean" {:required [:digitalocean-name :digitalocean-region
                                :digitalocean-control-plane-size
                                :digitalocean-worker-size :digitalocean-image
                                :digitalocean-ssh-key-fingerprint
                                :digitalocean-vpc-cidr
                                :digitalocean-ssh-sources
                                :digitalocean-api-sources]
                    :secrets [:do-token]
                    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}}
   :provider-dns
   {"cloudflare" {:required [:cloudflare-zone :application-host]
                  :secrets [:cloudflare-api-token]
                  :tofu-env {}}
    "no-infra" {:required [] :secrets [] :tofu-env {}}}
   :provider-backend
   {"local" {:required [] :secrets [] :tofu-env {}}
    "s3" {:required [:s3-bucket :s3-region]
          :secrets [:s3-access-key-id :s3-secret-access-key]
          :tofu-env {:s3-access-key-id "AWS_ACCESS_KEY_ID"
                     :s3-secret-access-key "AWS_SECRET_ACCESS_KEY"}}
    "r2" {:required [:r2-bucket :r2-endpoint]
          :secrets [:r2-access-key-id :r2-secret-access-key]
          :tofu-env {:r2-access-key-id "AWS_ACCESS_KEY_ID"
                     :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}}}})

(def slots [:provider-compute :provider-dns :provider-backend])
(def profile-par (green-cli/par-name :profile))

(defn placeholder? [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x) (= "REPLACE_ME" (str/upper-case x))))))

(defn entry [opts slot] (get-in providers [slot (get opts slot)]))
(defn tofu-env [opts slot] (:tofu-env (entry opts slot) {}))
(defn- slot-keys [opts field] (mapcat #(get (entry opts %) field []) slots))
(defn- missing [opts ks] (keep #(when (placeholder? (get opts %)) %) ks))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. K8s takes profile from colors.yml only; "
          "an environment overlay could redirect remote state.")]))

(def ^:private semver-re #"^v[0-9]+\.[0-9]+\.[0-9]+$")
(def ^:private https-git-re #"^https://[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)+(?:\.git)?$")
(def ^:private dns-re #"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$")
(def ^:private cidr-re #"^(?:[0-9]{1,3}\.){3}[0-9]{1,3}/(?:[0-9]|[12][0-9]|3[0-2])$")
(def ^:private branch-re #"^[A-Za-z0-9._/-]+$")
(def ^:private path-re #"^\./[A-Za-z0-9._/-]+$")
(def ^:private profile-re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")

(defn valid-cidr? [value]
  (and (re-matches cidr-re (str value))
       (every? #(<= 0 % 255)
               (map parse-long (str/split (first (str/split (str value) #"/")) #"\.")))))

(def required-keys
  [:profile :workdir :kubernetes-distribution :kubernetes-version
   :kubernetes-cni :flannel-version :kubernetes-pod-cidr
   :kubernetes-service-cidr :flux-version
   :digitalocean-cloud-controller-version :repository :repository-branch
   :repository-path :control-plane-count :worker-count
   :external-dns-owner-id :cert-manager-acme-environment])

(defn state-errors [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing opts (concat required-keys (slot-keys opts :required))))
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (when-not (= "digitalocean" (:provider-compute opts))
      [":provider-compute must be digitalocean"])
    (when-not (= "kubeadm" (:kubernetes-distribution opts))
      [":kubernetes-distribution must be kubeadm"])
    (when-not (= "flannel" (:kubernetes-cni opts))
      [":kubernetes-cni must be flannel"])
    (when-not (= 1 (:control-plane-count opts))
      [":control-plane-count must be 1"])
    (when-not (= 1 (:worker-count opts))
      [":worker-count must be 1"])
    (when-not (true? (:digitalocean-cloud-controller opts))
      [":digitalocean-cloud-controller must be true"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (placeholder? (:profile opts))
                  (re-matches profile-re (str (:profile opts))))
      [":profile must be a safe 1-63 character name"])
    (for [k [:kubernetes-version :flannel-version :flux-version
             :digitalocean-cloud-controller-version]
          :let [v (get opts k)]
          :when (and (not (placeholder? v))
                     (not (re-matches semver-re (str v))))]
      (str k " must be an exact vMAJOR.MINOR.PATCH release"))
    (when (and (not (placeholder? (:repository opts)))
               (not (re-matches https-git-re (str (:repository opts)))))
      [":repository must be a public HTTPS Git URL"])
    (when-not (or (placeholder? (:repository-branch opts))
                  (re-matches branch-re (str (:repository-branch opts))))
      [":repository-branch contains unsupported characters"])
    (when-not (or (placeholder? (:repository-path opts))
                  (re-matches path-re (str (:repository-path opts))))
      [":repository-path must begin with ./"])
    (for [k [:application-host :cloudflare-zone]
          :let [v (get opts k)]
          :when (and (= "cloudflare" (:provider-dns opts))
                     (not (placeholder? v))
                     (not (re-matches dns-re (str v))))]
      (str k " must be a DNS name"))
    (when (and (= "cloudflare" (:provider-dns opts))
               (not (placeholder? (:application-host opts)))
               (not (placeholder? (:cloudflare-zone opts)))
               (not (str/ends-with? (str (:application-host opts))
                                    (str "." (:cloudflare-zone opts)))))
      [":application-host must be below :cloudflare-zone"])
    (for [k [:kubernetes-pod-cidr :kubernetes-service-cidr
             :digitalocean-vpc-cidr]
          :let [v (get opts k)]
          :when (and (not (placeholder? v)) (not (valid-cidr? v)))]
      (str k " must be a valid IPv4 CIDR"))
    (for [k [:digitalocean-ssh-sources :digitalocean-api-sources]
          :let [values (get opts k)]
          :when (and (not (placeholder? values))
                     (or (not (sequential? values))
                         (empty? values)
                         (some (complement valid-cidr?) values)))]
      (str k " must be a non-empty list of IPv4 CIDRs"))
    (when-not (contains? #{"production" "staging"}
                         (:cert-manager-acme-environment opts))
      [":cert-manager-acme-environment must be production or staging"]))))

(defn secret-errors
  ([opts] (secret-errors opts slots))
  ([opts selected]
   (map #(str "required credential is not set: " (green-cli/par-name %))
        (distinct
         (missing opts
                  (mapcat #(get (entry opts %) :secrets []) selected))))))
