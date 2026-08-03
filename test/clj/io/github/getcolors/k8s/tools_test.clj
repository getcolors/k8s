(ns io.github.getcolors.k8s.tools-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.validate-test :as vt]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(deftest stage-names-are-package-specific
  (is (= "k8s-infrastructure" tools/infrastructure-tool))
  (is (= "k8s-ansible-remote" tools/ansible-remote-tool)))

(deftest inventory-separates-control-plane-and-worker
  (let [parsed (json/parse-string
                (tools/inventory
                 (merge vt/base {:control_plane_public_ip "203.0.113.1"
                                 :control_plane_private_ip "10.20.0.2"
                                 :worker_public_ips ["203.0.113.2"]
                                 :worker_private_ips ["10.20.0.3"]})))]
    (is (= "10.20.0.2"
           (get-in parsed ["all" "children" "control_plane" "hosts"
                           "k8s-test-control-plane-1" "private_ip"])))
    (is (= "203.0.113.2"
           (get-in parsed ["all" "children" "workers" "hosts"
                           "k8s-test-worker-1" "ansible_host"])))))

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

(deftest load-infrastructure-accepts-the-system-environment
  (let [opts (assoc vt/base :workdir (temp-dir) :green/event :delete)]
    (with-redefs [sc/scaffold (fn [rendered _] rendered)
                  process/run (fn [& _] {:exit 0})
                  tofu/outputs (fn [& _] {:control_plane_public_ip "203.0.113.1"})]
      (let [result (tools/load-infrastructure-step opts)]
        (is (= :delete (:green/event result)))
        (is (:k8s/infrastructure-present? result))
        (is (= "203.0.113.1" (:control_plane_public_ip result)))))))

(deftest repeated-delete-skips-remote-cleanup-after-nodes-are-gone
  (let [opts (assoc vt/base :green/event :delete
                    :k8s/infrastructure-present? false)]
    (with-redefs [ansible/ansible-with-spec
                  (fn [& _] (throw (ex-info "must not run" {})))]
      (is (= opts (tools/ansible-remote-step opts))))))

(deftest workdir-resolves-beside-state
  (is (= "/srv/project/.colors/p/k8s-infrastructure"
         (tools/tool-dir {:workdir ".colors" :profile "p"
                          :green/state-file "/srv/project/colors.yml"}
                         tools/infrastructure-tool))))
