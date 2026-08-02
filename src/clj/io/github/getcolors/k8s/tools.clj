(ns io.github.getcolors.k8s.tools
  "Package-owned Talos image, infrastructure, platform, and acceptance stages."
  (:require [clojure.java.io :as io]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.operator :as operator]
            [io.github.getcolors.k8s.utils :as utils]
            [io.github.getcolors.k8s.validate :as validate]))

(def image-tool "k8s-image")
(def infrastructure-tool "k8s-infrastructure")
(def bootstrap-tool "k8s-bootstrap")
(def acceptance-tool "k8s-acceptance")
(def tofu-tools [infrastructure-tool])

(def ^:private root "io.github.getcolors.k8s.tools")
(def ^:private raw-template :io.github.getcolors.k8s/raw)
(def ^:private template-opts
  {:tag-open \< :tag-close \> :filter-open \{ :filter-close \}})

(defn template [path file] (keyword (str root "." path) file))
(defn spec [template target data]
  {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content]
  (spec raw-template target {:content content}))
(defn tool-dir [opts tool] (utils/tool-dir opts tool))

(defn credential-env
  "Provider/backend credentials translated only into child-process variables."
  [opts & slots]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [value (not-empty (str (get opts k)))]
                   [env-var value])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn process-result [opts label {:keys [exit out err]}]
  (if (zero? exit)
    (assoc opts :green/exit 0)
    (assoc opts :green/exit (max 1 exit)
                :green/err (str label " failed: "
                                (or (not-empty err) (not-empty out) "(no output)")))))

(defn image-specs [opts]
  (let [dir (tool-dir opts image-tool)]
    [(spec (template "image" "schematic.yaml") (str dir "/schematic.yaml") opts)
     (spec (template "image" "create.sh") (str dir "/create.sh") opts)
     (spec (template "image" "delete.sh") (str dir "/delete.sh") opts)]))

(defn image-step
  "Build/reuse the exact Talos snapshot, or delete it after infrastructure."
  [opts]
  (let [specs (image-specs opts)
        delete? (= :delete (:green/event opts))
        rendered (sc/scaffold (if delete?
                                (assoc opts :green/event :create)
                                opts)
                              specs)]
    (if (= :build (:green/event opts))
      rendered
      (let [script (str (tool-dir opts image-tool)
                        (if delete? "/delete.sh" "/create.sh"))
            result (process/run-with-timeout
                    ["bash" script]
                    {:extra-env (credential-env opts :provider-compute)}
                    (* 30 60 1000))
            outcome (process-result rendered "Talos image stage" result)]
        (if (or (wf/failed? outcome) (not delete?))
          outcome
          (sc/scaffold (assoc outcome :green/event :delete) specs))))))

(defn infrastructure-specs [opts]
  (let [dir (tool-dir opts infrastructure-tool)]
    [(spec (template "infrastructure" "main.tf") (str dir "/main.tf") opts)]))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)]
    (tofu/tofu-with-spec
     opts (infrastructure-specs opts)
     {:dir dir
      :env (credential-env opts :provider-compute :provider-dns)
      :output-key :k8s/outputs})))

(defn acme-server [opts]
  (if (= "staging" (:letsencrypt-environment opts))
    "https://acme-staging-v02.api.letsencrypt.org/directory"
    "https://acme-v02.api.letsencrypt.org/directory"))

(defn bootstrap-data [opts]
  (merge opts
         (utils/chart-versions opts)
         {:acme-server (acme-server opts)}))

(defn bootstrap-specs [opts]
  (let [dir (tool-dir opts bootstrap-tool)
        data (bootstrap-data opts)]
    (mapv (fn [file]
            (spec (template "bootstrap" file) (str dir "/" file) data))
          ["create.sh" "cilium-values.yaml" "ccm-values.yaml"
           "csi-values.yaml" "external-dns-values.yaml"
           "cert-manager-values.yaml" "platform.yaml" "gitops.yaml"])))

(defn cluster-env [opts configs]
  (let [outputs (:k8s/outputs opts)]
    (merge (credential-env opts :provider-compute :provider-dns)
           configs
           {"HCLOUD_NETWORK" (str (:network_id outputs))
            "INGRESS_IPV4" (str (:ingress_ipv4 outputs))})))

(defn bootstrap-step
  "Install Cilium and all controllers without persisting cluster credentials."
  [opts]
  (let [rendered (sc/scaffold opts (bootstrap-specs opts))]
    (if (or (= :build (:green/event opts)) (= :delete (:green/event opts)))
      rendered
      (operator/with-cluster-configs
       opts
       (fn [configs]
         (process-result
          rendered "cluster bootstrap"
          (process/run-with-timeout
           ["bash" (str (tool-dir opts bootstrap-tool) "/create.sh")]
           {:extra-env (cluster-env opts configs)}
           (* 30 60 1000))))))))

(defn acceptance-specs [opts]
  (let [dir (tool-dir opts acceptance-tool)]
    [(spec (template "acceptance" "acceptance.sh")
           (str dir "/acceptance.sh") opts)]))

(defn acceptance-step [opts]
  (let [rendered (sc/scaffold opts (acceptance-specs opts))]
    (if (or (= :build (:green/event opts)) (= :delete (:green/event opts)))
      rendered
      (operator/with-cluster-configs
       opts
       (fn [configs]
         (process-result
          rendered "acceptance"
          (process/run-with-timeout
           ["bash" (str (tool-dir opts acceptance-tool) "/acceptance.sh")]
           {:extra-env (merge configs
                              {"EXPECTED_INGRESS_IPV4"
                               (str (get-in opts [:k8s/outputs :ingress_ipv4]))})}
           (* 20 60 1000))))))))

(defn generated-cleanup-step [opts]
  (-> opts
      (sc/scaffold (bootstrap-specs opts))
      (sc/scaffold (acceptance-specs opts))))
