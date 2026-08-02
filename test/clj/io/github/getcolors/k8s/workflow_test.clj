(ns io.github.getcolors.k8s.workflow-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.validate-test :as vt]
            [io.github.getcolors.k8s.workflow :as workflow]))

(defn temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))
(defn next-steps [event step] (vec (rest (workflow/wire-fn step {:green/event event}))))

(deftest create-orders-owned-image-infrastructure-bootstrap-and-acceptance
  (is (= [:k8s/image] (next-steps :create :k8s/start)))
  (is (= [:k8s/infrastructure] (next-steps :create :k8s/image)))
  (is (= [:k8s/bootstrap] (next-steps :create :k8s/infrastructure)))
  (is (= [:k8s/acceptance] (next-steps :create :k8s/bootstrap))))

(deftest delete-destroys-infrastructure-before-image
  (is (= [:k8s/infrastructure] (next-steps :delete :k8s/start)))
  (is (= [:k8s/image] (next-steps :delete :k8s/infrastructure)))
  (is (= [:k8s/generated-cleanup] (next-steps :delete :k8s/image))))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc vt/base :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc vt/base :green/event :create :green/dry-run true) {})))))

(deftest real-create-needs-every-selected-provider-credential
  (let [result (workflow/start-step (assoc vt/base :green/event :create) {})]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "COLORS_PAR_HCLOUD_TOKEN"))
    (is (str/includes? (:green/err result) "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? (:green/err result) "COLORS_PAR_R2_ACCESS_KEY_ID"))))

(deftest delete-guard-requires-one-run-override
  (let [credentials {"COLORS_PAR_HCLOUD_TOKEN" "h"
                     "COLORS_PAR_CLOUDFLARE_API_TOKEN" "c"
                     "COLORS_PAR_R2_ACCESS_KEY_ID" "a"
                     "COLORS_PAR_R2_SECRET_ACCESS_KEY" "s"}]
    (is (= 2 (:green/exit (workflow/start-step
                           (assoc vt/base :green/event :delete) credentials))))
    (is (= 0 (:green/exit (workflow/start-step
                           (assoc vt/base :green/event :delete)
                           (assoc credentials "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false")))))))

(deftest backend-key-is-profile-and-package-stage
  (let [dir (temp-dir) opts (assoc vt/base :workdir dir)
        result ((workflow/backend-advice tools/infrastructure-tool) opts)
        backend (slurp (str (tools/tool-dir result tools/infrastructure-tool)
                            "/backend.tf.json"))]
    (is (str/includes? backend "k8s-fixture/k8s-infrastructure.tfstate"))))

(deftest whole-build-renders-all-stages
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :build :workdir dir))
        root (str dir "/k8s-fixture/")]
    (is (= 0 (:green/exit result)) (:green/err result))
    (doseq [file ["k8s-image/create.sh" "k8s-image/schematic.yaml"
                  "k8s-infrastructure/main.tf" "k8s-infrastructure/backend.tf.json"
                  "k8s-bootstrap/create.sh" "k8s-bootstrap/platform.yaml"
                  "k8s-bootstrap/gitops.yaml" "k8s-acceptance/acceptance.sh"]]
      (is (.exists (io/file (str root file))) file))))

(deftest dry-run-touches-nothing
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :create :green/dry-run true
                                      :workdir dir))]
    (is (= 0 (:green/exit result)))
    (is (empty? (seq (.listFiles (io/file dir)))))))
