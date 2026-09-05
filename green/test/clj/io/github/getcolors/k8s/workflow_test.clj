(ns io.github.getcolors.k8s.workflow-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.tools-test :as tt]
            [io.github.getcolors.k8s.validate-test :as vt]
            [io.github.getcolors.k8s.workflow :as workflow]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

;; The compute state is read once per run, through `tools/state-output`, on
;; a real create or delete. Every lifecycle test stubs it: nil is a readable
;; state holding no compute, a map is a recorded `params`, and a throw is a
;; backend that cannot be read.
(defn- start
  ([opts] (start opts nil {}))
  ([opts state] (start opts state {}))
  ([opts state env]
   (with-redefs [tools/state-output (fn [_] state)]
     (workflow/start-step opts env))))

(defn- start-unreadable [opts env]
  ;; The shape `green.tofu/outputs` throws: an ex-info carrying `:dir`. Only
  ;; that is an unreadable backend; anything else propagates as a defect.
  (with-redefs [tools/state-output
                (fn [_] (throw (ex-info "tofu output failed: no backend" {:dir "x"})))]
    (workflow/start-step opts env)))

(def credentials
  {"COLORS_PAR_DO_TOKEN" "x"
   "COLORS_PAR_CLOUDFLARE_API_TOKEN" "y"})

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

(deftest build-and-dry-run-need-no-credentials-and-never-read-the-state
  ;; A throwing reader proves nothing on these paths reaches the backend.
  (doseq [opts [(assoc vt/base :green/event :build)
                (assoc vt/base :green/event :create :green/dry-run true)
                (assoc vt/base :green/event :delete :green/dry-run true)]]
    (is (= 0 (:green/exit (start-unreadable opts {}))))))

(deftest real-lifecycle-needs-secrets-and-delete-override
  (is (= 2 (:green/exit (start (assoc vt/base :green/event :create)))))
  (is (= 0 (:green/exit (start (assoc vt/base :green/event :create) nil credentials))))
  (is (= 2 (:green/exit (start (assoc vt/base :green/event :delete) nil credentials))))
  (is (= 0 (:green/exit (start (assoc vt/base :green/event :delete) nil
                               (assoc credentials "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"))))))

(deftest a-provider-switch-is-refused-before-the-credentials
  ;; Standard §4: the recorded provider is compared with the selected one
  ;; before the secrets, so the actionable error is what the operator reads.
  (doseq [event [:create :delete]]
    (let [r (start (assoc vt/base :green/event event :compute-prevent-destroy false)
                   (assoc tt/cluster :provider "vultr"))]
      (is (= 2 (:green/exit r)) event)
      (is (str/includes? (:green/err r)
                         "state holds a vultr machine; set provider-compute back to vultr and delete first"))
      (is (not (str/includes? (:green/err r) "required credential is not set"))))))

(deftest legacy-state-is-accepted-on-the-default-provider
  ;; A recorded state without `provider` predates the package recording one:
  ;; it is a digitalocean cluster, which is what is selected.
  (doseq [event [:create :delete]]
    (let [r (start (assoc vt/base :green/event event :compute-prevent-destroy false)
                   (dissoc tt/cluster :provider))]
      (is (= 2 (:green/exit r)) event)
      (is (not (str/includes? (:green/err r) "state holds")))
      (is (str/includes? (:green/err r) "required credential is not set")))))

(deftest a-matching-provider-passes-to-the-credentials
  (let [r (start (assoc vt/base :green/event :create) tt/cluster)]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))

(deftest an-unreadable-backend-counts-as-no-state-on-create
  ;; A fresh clone has no readable state and must still be able to create.
  (let [r (start-unreadable (assoc vt/base :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "could not read")))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))

(deftest a-real-create-on-a-fresh-work-directory-reports-the-credentials-not-a-crash
  ;; No state stub: the real `state-output` runs against a work directory
  ;; that holds no stage yet, as a fresh clone's does. The init cannot run
  ;; there, which the reader reports as the SDK's step error; ONCE's
  ;; `read-state` counts that as an unreadable state, so the create reports
  ;; its credentials instead of crashing.
  (let [work (temp-dir)
        r (workflow/start-step (assoc vt/base :workdir work :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (str (:green/err r)) "COLORS_PAR_DO_TOKEN"))
    (is (not (str/includes? (str (:green/err r)) "could not read")))
    (is (empty? (seq (.listFiles (io/file work)))))))

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
