(ns io.github.getcolors.k8s.tools
  "DigitalOcean infrastructure, kubeadm Ansible, and acceptance stages."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.utils :as utils]
            [io.github.getcolors.k8s.validate :as validate]))

(def infrastructure-tool "k8s-infrastructure")
(def ansible-local-tool "k8s-ansible-local")
(def ansible-remote-tool "k8s-ansible-remote")
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

(defn credential-env [opts & slots]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [value (not-empty (str (get opts k)))]
                   [env-var value])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn infrastructure-specs [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        data (assoc opts
                    :digitalocean-ssh-sources-json
                    (json/generate-string (:digitalocean-ssh-sources opts))
                    :digitalocean-api-sources-json
                    (json/generate-string (:digitalocean-api-sources opts)))]
    [(spec (template "infrastructure" "main.tf") (str dir "/main.tf") data)]))

(def fallback-outputs
  {:control_plane_public_ip "192.168.0.10"
   :control_plane_private_ip "10.20.0.10"
   :worker_public_ips ["192.168.0.11"]
   :worker_private_ips ["10.20.0.11"]})

(defn- output-map [result]
  (some-> (:k8s/outputs result) walk/keywordize-keys))

(declare process-result)

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        result (tofu/tofu-with-spec
                opts (infrastructure-specs opts)
                {:dir dir
                 :env (credential-env opts :provider-compute)
                 :output-key :k8s/outputs})]
    (cond
      (wf/failed? result) result
      (= :delete (:green/event opts)) result
      (= :build (:green/event opts)) (merge result fallback-outputs)
      :else (merge result fallback-outputs (output-map result)))))

(defn load-infrastructure-step
  "Load node addresses from remote state without planning or changing cloud resources."
  [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        rendered (sc/scaffold (assoc opts :green/event :build)
                              (infrastructure-specs opts))
        env (merge (System/getenv) (credential-env opts :provider-compute))
        init (process/run ["tofu" (str "-chdir=" dir) "init"
                           "-input=false" "-no-color"] {:extra-env env})]
    (if-not (zero? (:exit init))
      (process-result rendered "infrastructure state initialization" init)
      (try
        (merge rendered fallback-outputs (tofu/outputs dir env))
        (catch Throwable t
          (assoc rendered :green/exit 1
                          :green/err (str "infrastructure state output failed: "
                                          (or (ex-message t) (str (class t))))))))))

(defn data-fn [opts]
  (merge fallback-outputs opts
         {:host-alias (utils/host-alias opts)
          :kubernetes-minor (utils/kubernetes-minor (:kubernetes-version opts))
          :kubernetes-package-version
          (utils/kubernetes-package-version (:kubernetes-version opts))}))

(defn inventory [opts]
  (let [data (data-fn opts)
        cp-name (str (:digitalocean-name data) "-control-plane-1")
        workers (map-indexed
                 (fn [index [public private]]
                   [(str (:digitalocean-name data) "-worker-" (inc index))
                    {:ansible_host public :ansible_user "root"
                     :private_ip private}])
                 (map vector (:worker_public_ips data)
                      (:worker_private_ips data)))]
    (json/generate-string
     {:all {:children
            {:control_plane
             {:hosts {cp-name {:ansible_host (:control_plane_public_ip data)
                               :ansible_user "root"
                               :private_ip (:control_plane_private_ip data)}}}
             :workers {:hosts (into (sorted-map) workers)}
             :k8s_cluster {:children {:control_plane {} :workers {}}}}}}
     {:pretty true})))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (data-fn opts)]
    [(spec (template "ansible-local" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini") (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml") (str dir "/main.yml") data)]))

(defn ansible-local-step [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (data-fn opts)
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec
     opts
     {:dir dir :inventory "inventory.ini"
      :playbooks {:create "main.yml" :delete "main.yml"}
      :extra-vars {:host_alias (:host-alias data)
                   :ip (:control_plane_public_ip data)
                   :block_state (if delete? "absent" "present")}}
     (ansible-local-specs opts))))

(defn ansible-remote-specs [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)]
    [(spec (template "ansible-remote" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-remote" "create.yml") (str dir "/create.yml") data)
     (spec (template "ansible-remote" "delete.yml") (str dir "/delete.yml") data)
     (spec (template "ansible-remote" "gitops.yml") (str dir "/gitops.yml") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-remote-step [opts]
  (let [dir (tool-dir opts ansible-remote-tool)]
    (ansible/ansible-with-spec
     opts
     {:dir dir :inventory "inventory.json"
      :playbooks {:create "create.yml" :delete "delete.yml"}
      :host-key-checking false}
     (ansible-remote-specs opts))))

(defn acceptance-specs [opts]
  (let [dir (tool-dir opts acceptance-tool)]
    [(spec (template "acceptance" "acceptance.sh")
           (str dir "/acceptance.sh") (data-fn opts))]))

(defn process-result [opts label {:keys [exit out err]}]
  (if (zero? exit)
    (assoc opts :green/exit 0)
    (assoc opts :green/exit (max 1 exit)
                :green/err (str label " failed: "
                                (or (not-empty err) (not-empty out) "(no output)")))))

(defn acceptance-step [opts]
  (let [rendered (sc/scaffold opts (acceptance-specs opts))]
    (if (or (= :build (:green/event opts)) (= :delete (:green/event opts)))
      rendered
      (process-result
       rendered "acceptance"
       (process/run-with-timeout
        ["bash" (str (tool-dir opts acceptance-tool) "/acceptance.sh")]
        {:extra-env nil} (* 25 60 1000))))))

(defn generated-cleanup-step [opts]
  (-> opts
      (sc/scaffold (ansible-local-specs opts))
      (sc/scaffold (ansible-remote-specs opts))
      (sc/scaffold (acceptance-specs opts))))
