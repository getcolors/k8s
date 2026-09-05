(ns io.github.getcolors.k8s.tools-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.validate-test :as vt]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(def cluster
  "A recorded `params`, as the compute stage outputs it after adoption."
  {:provider "digitalocean"
   :vpc_id "9c0a1b2c-3d4e-4f60-8a7b-1c2d3e4f5a6b"
   :nodes [{:index 0 :role "control-plane" :name "k8s-test-control-plane-1"
            :ip "203.0.113.1" :vpc_ip "10.20.0.2" :user "root" :sudoer "root"}
           {:index 0 :role "worker" :name "k8s-test-worker-1"
            :ip "203.0.113.2" :vpc_ip "10.20.0.3" :user "root" :sudoer "root"}]})

(def legacy-outputs
  "The pre-adoption state, in the recorded scalar-plus-list shape."
  (walk/keywordize-keys
   (json/parse-string (slurp "test/fixtures/legacy-outputs.json"))))

(defn- read-state
  "`state-output` over stubbed tofu: an init that succeeds and an output
  read returning `outputs`."
  [opts outputs]
  (with-redefs [process/run (fn [& _] {:exit 0 :out "" :err ""})
                tofu/outputs (fn [& _] outputs)]
    (tools/state-output opts)))

(deftest stage-names-are-package-specific
  (is (= "k8s-infrastructure" tools/infrastructure-tool))
  (is (= "k8s-ansible-remote" tools/ansible-remote-tool)))

(deftest inventory-separates-control-plane-and-worker
  (let [parsed (json/parse-string
                (tools/inventory (assoc vt/base :once/cluster cluster)))]
    (is (= "10.20.0.2"
           (get-in parsed ["all" "children" "control_plane" "hosts"
                           "k8s-test-control-plane-1" "private_ip"])))
    (is (= "203.0.113.2"
           (get-in parsed ["all" "children" "workers" "hosts"
                           "k8s-test-worker-1" "ansible_host"])))))

(deftest build-renders-fallback-nodes-under-the-packages-own-names
  ;; No adopted cluster: ONCE's fallbacks on TEST-NET-1 and the owned VPC's
  ;; CIDR, named the way the template names the droplets.
  (is (= [{:role "control-plane" :index 0 :name "k8s-test-control-plane-1"
           :ip "192.0.2.10" :vpc_ip "10.20.0.10" :user "root" :sudoer "root"}
          {:role "worker" :index 0 :name "k8s-test-worker-1"
           :ip "192.0.2.11" :vpc_ip "10.20.0.11" :user "root" :sudoer "root"}]
         (tools/nodes vt/base)))
  (is (= "192.0.2.10" (tools/entry-ip vt/base)))
  (is (= "203.0.113.1" (tools/entry-ip (assoc vt/base :once/cluster cluster))))
  (is (= "00000000-0000-0000-0000-000000000000"
         (:digitalocean_vpc_id (tools/data-fn vt/base))))
  (is (= (:vpc_id cluster)
         (:digitalocean_vpc_id (tools/data-fn (assoc vt/base :once/cluster cluster))))))

(deftest params-errors-require-the-vpc-id
  (is (= ["compute state carries no vpc_id"]
         (tools/params-errors (dissoc cluster :vpc_id))))
  (is (= ["compute state carries no vpc_id"]
         (tools/params-errors (assoc cluster :vpc_id " "))))
  (is (nil? (tools/params-errors cluster))))

(deftest the-reader-returns-params-nothing-or-the-legacy-translation
  (testing "a recorded params output, keywordized"
    (is (= cluster (read-state vt/base {:params (walk/stringify-keys cluster)}))))
  (testing "a readable state with no outputs at all"
    (is (nil? (read-state vt/base {}))))
  (testing "the pre-adoption outputs become params"
    (is (= cluster (read-state vt/base legacy-outputs))))
  (testing "the worker lists must agree"
    (try (read-state vt/base (assoc legacy-outputs :worker_private_ips []))
         (is false "must refuse")
         (catch clojure.lang.ExceptionInfo e
           (is (= "legacy state lists 1 worker public addresses and 0 private addresses; refusing to guess the cluster"
                  (ex-message e)))
           (is (contains? (ex-data e) :dir)))))
  (testing "the VPC id must be recorded"
    (doseq [outputs [(dissoc legacy-outputs :digitalocean_vpc_id)
                     (assoc legacy-outputs :digitalocean_vpc_id "")]]
      (try (read-state vt/base outputs)
           (is false "must refuse")
           (catch clojure.lang.ExceptionInfo e
             (is (= "legacy state carries no digitalocean_vpc_id" (ex-message e)))
             (is (contains? (ex-data e) :dir))))))
  (testing "a failed init is the SDK's step error"
    (with-redefs [process/run (fn [& _] {:exit 1 :out "" :err "no backend"})]
      (try (tools/state-output vt/base)
           (is false "must throw")
           (catch clojure.lang.ExceptionInfo e
             (is (str/includes? (ex-message e) "no backend"))
             (is (contains? (ex-data e) :dir)))))))

(deftest infrastructure-renders-owned-vpc-nodes-and-firewalls
  (let [dir (temp-dir)
        opts (assoc vt/base :workdir dir :profile "render" :green/event :build)
        specs (tools/infrastructure-specs opts)]
    (sc/scaffold opts specs)
    (let [hcl (slurp (str (tools/tool-dir opts tools/infrastructure-tool) "/main.tf"))]
      (is (str/includes? hcl "resource \"digitalocean_vpc\" \"cluster\""))
      (is (str/includes? hcl "resource \"digitalocean_droplet\" \"control_plane\""))
      (is (str/includes? hcl "203.0.113.10/32"))
      (is (str/includes? hcl "prevent_destroy = true"))
      (is (not (str/includes? hcl "DIGITALOCEAN_TOKEN"))))))

(deftest remote-render-pins-components-and-keeps-secret-lookups
  (let [dir (temp-dir)
        opts (assoc vt/base :workdir dir :profile "render" :green/event :build)
        result (tools/ansible-remote-step opts)
        root (tools/tool-dir result tools/ansible-remote-tool)
        play (slurp (str root "/create.yml"))]
    (is (str/includes? play "v1.36.3"))
    (is (str/includes? play "v0.28.8"))
    (is (str/includes? play "v0.1.68"))
    (is (str/includes? play "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? play "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (not (str/includes? play "fixture-secret")))))

(defn- load-with
  "`load-infrastructure-step` on a delete over a stubbed `state-output`:
  `state` is what the reader returns, or a function that throws."
  [state]
  (let [opts (assoc vt/base :workdir (temp-dir) :green/event :delete)]
    (with-redefs [sc/scaffold (fn [rendered _] rendered)
                  tools/state-output (if (fn? state) state (fn [_] state))]
      (tools/load-infrastructure-step opts))))

(deftest a-real-delete-adopts-the-recorded-cluster
  (let [result (load-with cluster)]
    (is (= 0 (:green/exit result)))
    (is (= :delete (:green/event result)))
    (is (= cluster (:once/cluster result)))
    (is (= "203.0.113.1" (tools/entry-ip result))))
  ;; A readable state holding no compute leaves the cluster unadopted; the
  ;; remote cleanup then skips itself.
  (let [result (load-with nil)]
    (is (= 0 (:green/exit result)))
    (is (not (contains? result :once/cluster)))))

(deftest a-real-delete-refuses-a-partial-cluster
  (let [result (load-with (update cluster :nodes subvec 0 1))]
    (is (= 1 (:green/exit result)))
    (is (= "the compute stage did not report nodes this package declares: worker-0"
           (:green/err result))))
  (let [result (load-with (dissoc cluster :vpc_id))]
    (is (= 1 (:green/exit result)))
    (is (= "compute state carries no vpc_id" (:green/err result)))))

(deftest an-unreadable-backend-fails-a-real-delete-closed
  ;; Swallowing it is how a teardown ends up converging against 192.0.2.10.
  (let [result (load-with (fn [_] (throw (ex-info "tofu output failed: no backend" {:dir "x"}))))]
    (is (= 1 (:green/exit result)))
    (is (str/includes? (:green/err result) "could not read the infrastructure state for the delete cleanup"))
    (is (str/includes? (:green/err result) "no backend")))
  ;; A legacy state the reader refuses is the same fail-closed path.
  (let [result (with-redefs [process/run (fn [& _] {:exit 0 :out "" :err ""})
                             tofu/outputs (fn [& _] (dissoc legacy-outputs :digitalocean_vpc_id))]
                 (load-with tools/state-output))]
    (is (= 1 (:green/exit result)))
    (is (str/includes? (:green/err result) "legacy state carries no digitalocean_vpc_id"))))

(deftest a-real-create-refuses-nil-and-partial-compute-outputs
  (let [opts (assoc vt/base :green/event :create)
        create (fn [outputs]
                 (with-redefs [tofu/tofu-with-spec
                               (fn [opts & _] (cond-> (assoc opts :green/exit 0)
                                                outputs (assoc :tofu/outputs {:params outputs})))]
                   (tools/infrastructure-step opts)))]
    (let [result (create nil)]
      (is (= 1 (:green/exit result)))
      (is (= "compute produced no params output; refusing to converge against the documentation addresses"
             (:green/err result))))
    (let [result (create (update cluster :nodes subvec 1))]
      (is (= 1 (:green/exit result)))
      (is (= "the compute stage did not report nodes this package declares: control-plane-0"
             (:green/err result))))
    (let [result (create (dissoc cluster :vpc_id))]
      (is (= 1 (:green/exit result)))
      (is (= "compute state carries no vpc_id" (:green/err result))))
    (let [result (create cluster)]
      (is (= 0 (:green/exit result)))
      (is (= cluster (:once/cluster result))))))

(deftest repeated-delete-skips-remote-cleanup-after-nodes-are-gone
  (let [opts (assoc vt/base :green/event :delete)]
    (with-redefs [ansible/ansible-with-spec
                  (fn [& _] (throw (ex-info "must not run" {})))]
      (is (= opts (tools/ansible-remote-step opts))))))

(deftest workdir-resolves-beside-state
  (is (= "/srv/project/.colors/p/k8s-infrastructure"
         (tools/tool-dir {:workdir ".colors" :profile "p"
                          :green/state-file "/srv/project/colors.yml"}
                         tools/infrastructure-tool))))
