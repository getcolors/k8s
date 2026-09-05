(ns io.github.getcolors.k8s.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.k8s.validate :as validate]
            [io.github.getcolors.once.compute-cluster :as cluster]))

(def base
  {:profile "k8s-test" :workdir ".colors"
   :provider-compute "digitalocean" :provider-dns "cloudflare"
   :provider-backend "local" :compute-prevent-destroy true
   :kubernetes-distribution "kubeadm" :kubernetes-version "v1.36.3"
   :kubernetes-cni "flannel" :flannel-version "v0.28.8"
   :kubernetes-pod-cidr "10.244.0.0/16"
   :kubernetes-service-cidr "10.96.0.0/12"
   :control-plane-count 1 :worker-count 1 :flux-version "v2.9.3"
   :digitalocean-cloud-controller-version "v0.1.68"
   :digitalocean-cloud-controller true
   :repository "https://github.com/getcolors/k8s-helloworld.git"
   :repository-branch "main" :repository-path "./clusters/k8s-digitalocean"
   :digitalocean-name "k8s-test" :digitalocean-region "ams3"
   :digitalocean-control-plane-size "s-2vcpu-4gb"
   :digitalocean-worker-size "s-2vcpu-4gb"
   :digitalocean-image "ubuntu-24-04-x64"
   :digitalocean-vpc-cidr "10.20.0.0/20"
   :digitalocean-ssh-sources ["203.0.113.10/32"]
   :digitalocean-api-sources ["203.0.113.10/32"]
   :application-host "hello.example.com" :cloudflare-zone "example.com"
   :external-dns-owner-id "k8s-test"
   :cert-manager-acme-environment "production"})

(def optout
  "The opt-out twin: an operator-registered key, by id or fingerprint."
  (assoc base :digitalocean-ssh-keys "fingerprint"))

(defn- matching [opts re]
  (filter #(re-find re %) (validate/state-errors opts)))

(deftest complete-state-is-valid
  (is (= [] (validate/state-errors base))))

(deftest both-keypair-modes-are-renderable-and-the-old-key-name-is-refused
  ;; The SSH Keypair Standard has two modes and conformance means both hold.
  (is (= [] (validate/state-errors optout)))
  (is (validate/keygen? base))
  (is (not (validate/keygen? optout)))
  (is (empty? (matching base #"digitalocean-ssh-keys"))
      "the machine key is never required: its absence is keygen mode")
  (is (some #{":digitalocean-ssh-key-fingerprint is now :digitalocean-ssh-keys; rename it in colors.yml, or leave it out so the deployment owns its keypair"}
            (validate/state-errors (assoc base :digitalocean-ssh-key-fingerprint "fingerprint")))
      "the one desired-state migration: the key moved to the standard's name"))

(deftest reports-all-missing-and-invalid-values
  (let [errors (validate/state-errors
                (-> base
                    (dissoc :repository :digitalocean-region)
                    (assoc :kubernetes-version "latest"
                           :worker-count 3
                           :digitalocean-ssh-sources ["world"])))]
    (is (>= (count errors) 5))
    (is (some #(str/includes? % ":repository") errors))
    (is (some #(str/includes? % ":digitalocean-region") errors))))

(deftest package-is-kubeadm-flannel-digitalocean
  (is (seq (matching (assoc base :provider-compute "hcloud") #"digitalocean")))
  (is (seq (matching (assoc base :kubernetes-distribution "talos") #"kubeadm")))
  (is (seq (matching (assoc base :kubernetes-cni "cilium") #"flannel"))))

(deftest topology-and-cidrs-are-restricted
  (is (seq (matching (assoc base :control-plane-count 3) #"control-plane-count")))
  (is (seq (matching (assoc base :digitalocean-api-sources ["0.0.0.0/99"])
                     #"api-sources"))))

(deftest compute-checks-are-the-standards-in-once-words
  ;; The source lists, the owned VPC's CIDR and the selection are ONCE's
  ;; checks over `spec`; the package no longer words them itself.
  (is (= [":digitalocean-api-sources entry \"world\" is not an IPv4 or IPv6 CIDR"]
         (matching (assoc base :digitalocean-api-sources ["world"]) #"api-sources")))
  (is (= [":digitalocean-ssh-sources must list at least one CIDR"]
         (matching (assoc base :digitalocean-ssh-sources []) #"ssh-sources")))
  (is (= [":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]
         (matching (assoc base :digitalocean-vpc-cidr "10.20.0.1/20") #"vpc-cidr")))
  (is (= [":digitalocean-vpc-cidr is required"]
         (matching (dissoc base :digitalocean-vpc-cidr) #"vpc-cidr")))
  (is (= [":provider-compute must be one of digitalocean"]
         (matching (assoc base :provider-compute "hcloud") #"provider-compute")))
  ;; A created network is this package's to own: compute's DigitalOcean
  ;; "must not create a VPC" refusal is filtered, never reported.
  (is (empty? (matching base #"must be absent"))))

(deftest spec-content-is-the-two-role-topology
  (is (= [] (cluster/spec-errors validate/spec)))
  (is (= ["control-plane" "worker"] (map :role (:roles validate/spec))))
  (is (= [1 1] (map :count (:roles validate/spec))))
  (is (= [:control-plane-count :worker-count] (map :count-key (:roles validate/spec))))
  (is (= {:role "control-plane" :index 0} (:entry validate/spec)))
  (is (= {:mode :created :key :digitalocean-vpc-cidr}
         (get-in validate/spec [:registry "digitalocean" :network])))
  (is (= "digitalocean" (:default validate/spec)))
  (is (= {:non-empty ["ssh-sources" "api-sources"] :may-be-empty []} (:sources validate/spec)))
  (is (not (contains? validate/spec :fallback-subnet)))
  (is (= [] (cluster/topology-errors validate/spec base)))
  (is (= ["k8s-test" "k8s-test-control-plane" "k8s-test-worker"]
         (cluster/aliases validate/spec base))))

(deftest secret-errors-use-colors-variables
  (let [text (str/join "\n" (validate/secret-errors base))]
    (is (str/includes? text "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? text "COLORS_PAR_CLOUDFLARE_API_TOKEN")))
  (is (= [] (vec (validate/secret-errors
                  (assoc base :do-token "x" :cloudflare-api-token "y"))))))

(deftest profile-overlay-is-always-refused
  (is (str/includes? (first (validate/env-errors
                             {"COLORS_PAR_PROFILE" "other"}))
                     "COLORS_PAR_PROFILE")))
