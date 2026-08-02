(ns io.github.getcolors.k8s.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as cli]
            [io.github.getcolors.k8s.validate :as validate]))

(def base
  (cli/read-state (java.io.File. "test/fixtures/colors.yml")
                  (slurp "test/fixtures/colors.yml")))

(defn matching [opts re]
  (filter #(re-find re %) (validate/state-errors opts)))

(deftest exact-six-node-state-is-valid
  (is (= [] (validate/state-errors base)))
  (is (= 3 (:hcloud-control-plane-count base)))
  (is (= 3 (:hcloud-worker-count base))))

(deftest topology-is-fixed
  (is (seq (matching (assoc base :hcloud-control-plane-count 1) #"must be 3")))
  (is (seq (matching (assoc base :hcloud-worker-count 4) #"must be 3"))))

(deftest providers-are-package-owned-and-fixed
  (is (= [:provider-compute :provider-dns :provider-backend] validate/slots))
  (is (seq (matching (assoc base :provider-compute "digitalocean") #"hcloud")))
  (is (seq (matching (assoc base :provider-dns "no-infra") #"cloudflare")))
  (is (seq (matching (assoc base :provider-backend "gcs") #"unsupported"))))

(deftest exact-versions-and-safe-names-are-required
  (is (seq (matching (assoc base :talos-version "latest") #"talos-version")))
  (is (seq (matching (assoc base :kubernetes-version "1.36.3") #"kubernetes-version")))
  (is (seq (matching (assoc base :profile "../other") #":profile"))))

(deftest network-and-dns-inputs-are-checked
  (is (seq (matching (assoc base :admin-cidr "999.1.1.1/32") #":admin-cidr")))
  (is (seq (matching (assoc base :hcloud-node-subnet-cidr "10.1.1.0/24")
                     #"contained by")))
  (is (seq (matching (assoc base :hcloud-node-subnet-cidr "10.0.1.0/28")
                     #"at least 32 addresses")))
  (is (seq (matching (assoc base :kubernetes-api-hostname "api.other.example")
                     #"below :cloudflare-zone")))
  (is (seq (matching (assoc base :external-dns-policy "sync") #"upsert-only"))))

(deftest credential-errors-are-scoped
  (let [all (str/join "\n" (validate/secret-errors base))
        backend (str/join "\n" (validate/secret-errors base [:provider-backend]))]
    (is (str/includes? all "COLORS_PAR_HCLOUD_TOKEN"))
    (is (str/includes? all "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? backend "COLORS_PAR_R2_ACCESS_KEY_ID"))
    (is (not (str/includes? backend "HCLOUD_TOKEN")))))

(deftest profile-overlay-is-always-refused
  (is (str/includes? (first (validate/env-errors {"COLORS_PAR_PROFILE" "other"}))
                     "COLORS_PAR_PROFILE"))
  (is (nil? (validate/env-errors {"COLORS_PAR_HCLOUD_TOKEN" "x"}))))
