(ns io.github.getcolors.k8s.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as cli]
            [green.scaffold :as sc]
            [io.github.getcolors.k8s.tools :as tools]))

(def base
  (cli/read-state (java.io.File. "test/fixtures/colors.yml")
                  (slurp "test/fixtures/colors.yml")))

(defn temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(deftest package-owns-four-distinct-stages
  (is (= ["k8s-image" "k8s-infrastructure" "k8s-bootstrap" "k8s-acceptance"]
         [tools/image-tool tools/infrastructure-tool tools/bootstrap-tool tools/acceptance-tool])))

(deftest chart-app-version-mapping-is-explicit
  (is (= {:cilium-chart "1.20.0" :hcloud-ccm-chart "1.34.0"
          :hcloud-csi-chart "2.22.1" :external-dns-chart "1.21.1"
          :cert-manager-chart "v1.21.1"}
         (select-keys (tools/bootstrap-data base)
                      [:cilium-chart :hcloud-ccm-chart :hcloud-csi-chart
                       :external-dns-chart :cert-manager-chart]))))

(deftest infrastructure-renders-six-talos-nodes-and-private-api
  (let [dir (temp-dir) opts (assoc base :workdir dir :green/event :build)
        target (tools/tool-dir opts tools/infrastructure-tool)]
    (sc/scaffold opts (tools/infrastructure-specs opts))
    (let [hcl (slurp (str target "/main.tf"))]
      (is (str/includes? hcl "resource \"hcloud_server\" \"control_plane\""))
      (is (str/includes? hcl "resource \"hcloud_server\" \"worker\""))
      (is (str/includes? hcl "enable_public_interface = false"))
      (is (str/includes? hcl "source_ips = [\"203.0.113.10/32\", local.private_cidr]"))
      (is (str/includes? hcl "port       = \"51871\""))
      (is (str/includes? hcl "port       = \"8472\""))
      (is (str/includes? hcl "sensitive = true")))))

(deftest bootstrap-has-pins-runtime-secret-streaming-and-fixtures
  (let [dir (temp-dir) opts (assoc base :workdir dir :green/event :build)
        target (tools/tool-dir opts tools/bootstrap-tool)]
    (tools/bootstrap-step opts)
    (let [script (slurp (str target "/create.sh"))
          platform (slurp (str target "/platform.yaml"))]
      (doseq [pin ["1.20.0" "1.34.0" "2.22.1" "1.21.1" "v1.21.1" "v2.9.3"]]
        (is (str/includes? script pin)))
      (is (str/includes? script "env.HCLOUD_TOKEN"))
      (is (str/includes? script "env.CLOUDFLARE_API_TOKEN"))
      (is (not (str/includes? script "REPLACE_ME")))
      (is (str/includes? platform "kind: ClusterIssuer"))
      (is (str/includes? platform "kind: Ingress"))
      (is (str/includes? platform "kind: PersistentVolumeClaim")))))

(deftest credentials-map-only-to-child-environment
  (is (= {"HCLOUD_TOKEN" "secret"}
         (tools/credential-env (assoc base :provider-backend "local"
                                           :hcloud-token "secret")
                               :provider-compute))))
