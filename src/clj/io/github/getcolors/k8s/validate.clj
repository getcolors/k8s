(ns io.github.getcolors.k8s.validate
  "Credential-free desired-state rules and package-owned provider metadata."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]))

(def providers
  {:provider-compute
   {"hcloud" {:required [:hcloud-location :hcloud-network-zone
                          :hcloud-network-cidr :hcloud-node-subnet-cidr
                          :hcloud-control-plane-count :hcloud-control-plane-server-type
                          :hcloud-worker-count :hcloud-worker-server-type
                          :hcloud-api-load-balancer-type
                          :hcloud-ingress-load-balancer-type]
              :secrets [:hcloud-token]
              :tofu-env {:hcloud-token "HCLOUD_TOKEN"}}}
   :provider-dns
   {"cloudflare" {:required [:cloudflare-zone]
                  :secrets [:cloudflare-api-token]
                  :tofu-env {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}}}
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
(defn- slot-keys [opts selected field]
  (mapcat #(get (entry opts %) field []) selected))
(defn- missing [opts ks] (keep #(when (placeholder? (get opts %)) %) ks))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. K8s takes profile from colors.yml only; "
          "an environment overlay could redirect remote state.")]))

(def version-keys
  [:talos-version :kubernetes-version :cilium-version :flux-version
   :hcloud-cloud-controller-manager-version :hcloud-csi-driver-version
   :external-dns-version :cert-manager-version])
(def required-keys
  (concat [:profile :workdir :cluster-name :repository :repository-branch
           :repository-path :admin-cidr :kubernetes-api-hostname
           :ingress-test-hostname :external-dns-policy
           :letsencrypt-email :letsencrypt-environment]
          version-keys))
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

(defn- cidr-range [cidr]
  (let [[address prefix-text] (str/split (str cidr) #"/")
        prefix (parse-long prefix-text)
        number (reduce #(+ (* %1 256) %2) 0
                       (map parse-long (str/split address #"\.")))
        size (long (Math/pow 2 (- 32 prefix)))
        start (* (quot number size) size)]
    {:start start :end (+ start size -1) :prefix prefix}))

(defn- subnet-errors [opts]
  (let [network (:hcloud-network-cidr opts)
        subnet (:hcloud-node-subnet-cidr opts)]
    (when (and (valid-cidr? network) (valid-cidr? subnet))
      (let [outer (cidr-range network)
            inner (cidr-range subnet)]
        (concat
         (when (or (< (:start inner) (:start outer))
                   (> (:end inner) (:end outer)))
           [":hcloud-node-subnet-cidr must be contained by :hcloud-network-cidr"])
         (when (> (:prefix inner) 27)
           [":hcloud-node-subnet-cidr must provide at least 32 addresses (/27 or larger)"]))))))

(defn state-errors [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing opts (concat required-keys (slot-keys opts slots :required))))
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (when-not (= "hcloud" (:provider-compute opts))
      [":provider-compute must be hcloud"])
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (placeholder? (:profile opts))
                  (re-matches profile-re (str (:profile opts))))
      [":profile must be a safe 1-63 character name"])
    (when-not (= 3 (:hcloud-control-plane-count opts))
      [":hcloud-control-plane-count must be 3"])
    (when-not (= 3 (:hcloud-worker-count opts))
      [":hcloud-worker-count must be 3"])
    (for [k version-keys
          :let [v (get opts k)]
          :when (and (not (placeholder? v)) (not (re-matches semver-re (str v))))]
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
    (for [k [:cloudflare-zone :kubernetes-api-hostname :ingress-test-hostname]
          :let [v (get opts k)]
          :when (and (not (placeholder? v)) (not (re-matches dns-re (str v))))]
      (str k " must be a DNS name"))
    (for [k [:kubernetes-api-hostname :ingress-test-hostname]
          :let [v (str (get opts k)) zone (str (:cloudflare-zone opts))]
          :when (and (not (placeholder? v)) (not (placeholder? zone))
                     (not (str/ends-with? v (str "." zone))))]
      (str k " must be below :cloudflare-zone"))
    (for [k [:hcloud-network-cidr :hcloud-node-subnet-cidr :admin-cidr]
          :let [v (get opts k)]
          :when (and (not (placeholder? v)) (not (valid-cidr? v)))]
      (str k " must be a valid IPv4 CIDR"))
    (subnet-errors opts)
    (when-not (= "upsert-only" (:external-dns-policy opts))
      [":external-dns-policy must be upsert-only"])
    (when-not (boolean? (:external-dns-cloudflare-proxied opts))
      [":external-dns-cloudflare-proxied must be true or false"])
    (when-not (contains? #{"production" "staging"} (:letsencrypt-environment opts))
      [":letsencrypt-environment must be production or staging"])
    (for [k [:cilium-wireguard-enabled :cilium-ingress-enabled
             :hello-world-enabled :persistent-volume-test-enabled]
          :when (not (boolean? (get opts k)))]
      (str k " must be true or false")))))

(defn secret-errors
  ([opts] (secret-errors opts slots))
  ([opts selected]
   (map #(str "required credential is not set: " (green-cli/par-name %))
        (distinct (missing opts (slot-keys opts selected :secrets))))))
