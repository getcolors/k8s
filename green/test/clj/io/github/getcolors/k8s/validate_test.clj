(ns io.github.getcolors.k8s.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.k8s.validate :as validate]))

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
   :digitalocean-ssh-key-fingerprint "fingerprint"
   :digitalocean-vpc-cidr "10.20.0.0/20"
   :digitalocean-ssh-sources ["203.0.113.10/32"]
   :digitalocean-api-sources ["203.0.113.10/32"]
   :application-host "hello.example.com" :cloudflare-zone "example.com"
   :external-dns-owner-id "k8s-test"
   :cert-manager-acme-environment "production"})

(defn- matching [opts re]
  (filter #(re-find re %) (validate/state-errors opts)))

(deftest complete-state-is-valid
  (is (= [] (validate/state-errors base))))

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
