(ns io.github.getcolors.k8s.workflow-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.validate-test :as vt]
            [io.github.getcolors.k8s.workflow :as workflow]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(defn- next-steps [event step]
  (rest (workflow/wire-fn step {:green/event event})))

(deftest create-orders-infrastructure-before-kubeadm-and-acceptance
  (is (= [:k8s/infrastructure] (next-steps :create :k8s/start)))
  (is (= [:k8s/ansible-local] (next-steps :create :k8s/infrastructure)))
  (is (= [:k8s/ansible-remote] (next-steps :create :k8s/ansible-local)))
  (is (= [:k8s/acceptance] (next-steps :create :k8s/ansible-remote))))

(deftest delete-loads-state-and-removes-load-balancer-before-infrastructure
  (is (= [:k8s/load-infrastructure] (next-steps :delete :k8s/start)))
  (is (= [:k8s/ansible-remote]
         (next-steps :delete :k8s/load-infrastructure)))
  (is (= [:k8s/ansible-local] (next-steps :delete :k8s/ansible-remote)))
  (is (= [:k8s/infrastructure] (next-steps :delete :k8s/ansible-local))))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step
                          (assoc vt/base :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                          (assoc vt/base :green/event :create :green/dry-run true) {})))))

(deftest real-lifecycle-needs-secrets-and-delete-override
  (is (= 2 (:green/exit (workflow/start-step
                          (assoc vt/base :green/event :create) {}))))
  (let [env {"COLORS_PAR_DO_TOKEN" "x"
             "COLORS_PAR_CLOUDFLARE_API_TOKEN" "y"}]
    (is (= 0 (:green/exit (workflow/start-step
                            (assoc vt/base :green/event :create) env))))
    (is (= 2 (:green/exit (workflow/start-step
                            (assoc vt/base :green/event :delete) env))))
    (is (= 0 (:green/exit (workflow/start-step
                            (assoc vt/base :green/event :delete)
                            (assoc env "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false")))))))

(deftest backend-key-is-package-specific
  (let [dir (temp-dir)
        opts (merge vt/base {:profile "p" :workdir dir :provider-backend "r2"
                             :r2-bucket "b" :r2-endpoint "https://r2"})]
    ((workflow/backend-advice tools/infrastructure-tool) opts)
    (is (str/includes?
         (slurp (str (tools/tool-dir opts tools/infrastructure-tool)
                     "/backend.tf.json"))
         "p/k8s-infrastructure.tfstate"))))

(deftest whole-build-renders-all-stages
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :build
                              :workdir dir :profile "built"))
        root (str dir "/built/")]
    (is (= 0 (:green/exit result)))
    (doseq [file ["k8s-infrastructure/main.tf"
                  "k8s-infrastructure/backend.tf.json"
                  "k8s-ansible-local/main.yml"
                  "k8s-ansible-remote/create.yml"
                  "k8s-ansible-remote/delete.yml"
                  "k8s-ansible-remote/inventory.json"
                  "k8s-acceptance/acceptance.sh"]]
      (is (.exists (io/file (str root file))) file))))

(deftest dry-run-touches-nothing
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :create :green/dry-run true
                              :workdir dir :profile "dry"))]
    (is (= 0 (:green/exit result)))
    (is (empty? (seq (.listFiles (io/file dir)))))))
