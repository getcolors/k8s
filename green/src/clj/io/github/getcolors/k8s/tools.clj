(ns io.github.getcolors.k8s.tools
  "DigitalOcean infrastructure, kubeadm Ansible, and acceptance stages."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.providers :as provider-ops]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.ssh :as ssh]
            [io.github.getcolors.k8s.ssh-config :as ssh-config]
            [io.github.getcolors.k8s.utils :as utils]
            [io.github.getcolors.k8s.validate :as validate]
            [io.github.getcolors.once.compute-cluster :as cluster]))

(def infrastructure-tool "k8s-infrastructure")
(def ansible-local-tool "k8s-ansible-local")
(def ansible-remote-tool "k8s-ansible-remote")
(def acceptance-tool "k8s-acceptance")
(def tofu-tools [infrastructure-tool])

(def ^:private root "io.github.getcolors.k8s.tools")
(def ^:private template-opts sc/preserve-jinja-delimiters)

(defn template [path file] (keyword (str root "." path) file))
(defn spec [template target data]
  {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content]
  (sc/content-spec target content))
(defn tool-dir [opts tool] (utils/tool-dir opts tool))

(defn credential-env [opts & slots]
  (provider-ops/tool-env validate/providers opts
                         (conj (vec slots) :provider-backend)))

(defn infrastructure-specs [opts]
  ;; The machine-key paths are filled here as well as in preflight, so the
  ;; template renders the same bytes whichever step scaffolds it — the state
  ;; reader renders it as a build, and a test may render it alone.
  (let [opts (ssh/with-machine-key opts)
        dir (tool-dir opts infrastructure-tool)
        data (assoc opts
                    :digitalocean-ssh-sources-json
                    (json/generate-string (:digitalocean-ssh-sources opts))
                    :digitalocean-api-sources-json
                    (json/generate-string (:digitalocean-api-sources opts)))]
    [(spec (template "infrastructure" "main.tf") (str dir "/main.tf") data)]))

(def fallback-vpc-id
  "What `build` and `--dry-run` render as the VPC id: the compute stage
  owns the real one, recorded as `params.vpc_id`."
  "00000000-0000-0000-0000-000000000000")

(defn node-name
  "What this package calls a node — `<name>-<role>-<ordinal>`, 1-based,
  the rule the template gives the droplets. This is the package's own
  naming, kept over ONCE's fallback rule (Compute Cluster Standard §5,
  adoption renames nothing), and the name the legacy translation gives a
  node a pre-adoption state recorded without one."
  [opts role index]
  (str (:digitalocean-name opts) "-" role "-" (inc index)))

(defn nodes
  "The cluster's nodes in declared order — ONCE's `nodes` over the adopted
  `:once/cluster`: every field from state on a real run, the fallbacks on
  a build, with their names overridden to this package's own."
  [opts]
  (let [cluster (:once/cluster opts)
        nodes (cluster/nodes validate/spec opts cluster)]
    (if (nil? cluster)
      (mapv #(assoc % :name (node-name opts (:role %) (:index %))) nodes)
      nodes)))

(defn entry-ip
  "The address the bare `<profile>` alias points to: the control plane's,
  as ONCE's `ssh-config-hosts` resolves the spec's `:entry`."
  [opts]
  (:ip (first (cluster/ssh-config-hosts validate/spec opts (nodes opts)))))

(defn params-errors
  "The extension key this package puts inside `params` beside ONCE's:
  `vpc_id`, the deployment-owned VPC the cloud controller is told about.
  A real run is refused without it."
  [params]
  (let [v (:vpc_id params)]
    (when-not (and (string? v) (not (str/blank? v)))
      ["compute state carries no vpc_id"])))

(defn- with-params-check
  "After `resolved-cluster` or `adopt-state`: this package's `params-errors`
  over the adopted cluster, when there is one."
  [opts]
  (if-let [errors (and (not (wf/failed? opts))
                       (:once/cluster opts)
                       (seq (params-errors (:once/cluster opts))))]
    (assoc opts :green/exit 1 :green/err (str/join "\n" errors))
    opts))

(defn- non-blank? [x] (and (string? x) (not (str/blank? x))))

(defn legacy-params
  "The `params` a pre-adoption state describes. Before this package
  recorded `params`, its template output a scalar control plane
  (`control_plane_public_ip`, `control_plane_private_ip`) and two parallel
  worker lists; this builds control-plane node 0 from the scalars and
  worker i from the lists, names them by this package's own rule, and
  carries `vpc_id` from `digitalocean_vpc_id`. Refused — as the SDK's step
  error, so `read-state` reports it and a delete fails closed — when the
  two lists disagree or the VPC id is absent or blank. Nothing else reads a
  legacy output after adoption."
  [opts outputs]
  (let [dir (tool-dir opts infrastructure-tool)
        refuse (fn [msg] (throw (ex-info msg {:dir dir})))
        publics (vec (:worker_public_ips outputs))
        privates (vec (:worker_private_ips outputs))
        vpc-id (:digitalocean_vpc_id outputs)]
    (when (not= (count publics) (count privates))
      (refuse (str "legacy state lists " (count publics) " worker public addresses and "
                   (count privates) " private addresses; refusing to guess the cluster")))
    (when-not (non-blank? vpc-id)
      (refuse "legacy state carries no digitalocean_vpc_id"))
    {:provider "digitalocean"
     :vpc_id vpc-id
     :nodes (into [{:index 0 :role "control-plane"
                    :name (node-name opts "control-plane" 0)
                    :ip (:control_plane_public_ip outputs)
                    :vpc_ip (:control_plane_private_ip outputs)
                    :user "root" :sudoer "root"}]
                  (map-indexed (fn [i [ip vpc-ip]]
                                 {:index i :role "worker"
                                  :name (node-name opts "worker" i)
                                  :ip ip :vpc_ip vpc-ip
                                  :user "root" :sudoer "root"})
                               (map vector publics privates)))}))

(defn state-output
  "The reader ONCE's `read-state` takes: the compute `params` recorded in
  the infrastructure state, nil when the state holds no outputs at all, and
  the legacy translation above when it holds the pre-adoption outputs. The
  stage is initialised first so remote state is reachable without planning
  or changing cloud resources. An unreadable backend — a failed init, or
  whatever `green.tofu/outputs` throws — is the SDK's step error, which
  `read-state` turns into `{:error message}`; create and delete treat that
  differently. Kept local so tests can redefine it."
  [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        env (merge (into {} (System/getenv))
                   (credential-env opts :provider-compute))
        init (process/run ["tofu" (str "-chdir=" dir) "init"
                           "-input=false" "-no-color"] {:extra-env env})]
    (when-not (zero? (:exit init))
      (throw (ex-info (str "tofu init failed: "
                           (or (not-empty (:err init)) (not-empty (:out init)) "(no output)"))
                      {:dir dir})))
    (let [outputs (tofu/outputs dir env)]
      (cond
        (contains? outputs :params) (walk/keywordize-keys (:params outputs))
        (empty? outputs) nil
        :else (legacy-params opts outputs)))))

(def vpc-members-error
  "DigitalOcean's answer when a VPC is deleted while it still counts members."
  #"Can not delete VPC with members")

(def destroy-retry
  "How often a destroy is retried on `vpc-members-error`, and how long it
  waits between attempts. A var so a test can shorten the wait."
  {:attempts 4 :delay-ms 30000})

(defn destroy-with-drain
  "Run a destroy, retrying the DigitalOcean VPC race. Droplets are deleted
  asynchronously, and a destroy that reaches the deployment-owned VPC
  seconds later is refused with 409 `Can not delete VPC with members` — a
  race the next attempt wins once the members have drained (seen live on
  2026-09-05). Only that message is retried; every other failure is
  reported as is, on the first attempt."
  [run]
  (loop [attempt 1]
    (let [result (run)]
      (if (and (wf/failed? result)
               (re-find vpc-members-error (str (:green/err result)))
               (< attempt (:attempts destroy-retry)))
        (do (Thread/sleep (long (:delay-ms destroy-retry)))
            (recur (inc attempt)))
        result))))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        run #(tofu/tofu-with-spec
              opts (infrastructure-specs opts)
              {:dir dir
               :env (credential-env opts :provider-compute)})
        result (if (= :delete (:green/event opts)) (destroy-with-drain run) (run))]
    (cond
      (wf/failed? result) result
      (= :delete (:green/event opts)) result
      (= :build (:green/event opts)) result
      ;; A real converge never falls back: nil outputs and a partial cluster
      ;; are refused by ONCE, then the VPC id by this package.
      :else (with-params-check
              (cluster/resolved-cluster validate/spec opts result {}
                                        (cluster/output-params result))))))

(defn load-infrastructure-step
  "Adopt the cluster from remote state without planning or changing cloud
  resources: ONCE's `read-state` over `state-output`, then `adopt-state`,
  which fails closed on an unreadable backend and refuses a partial
  cluster, then this package's `params-errors`. A readable state holding no
  compute leaves `:once/cluster` absent, and the remote cleanup skips
  itself."
  [opts]
  (let [rendered (assoc (sc/scaffold (assoc opts :green/event :build)
                                     (infrastructure-specs opts))
                        :green/event (:green/event opts))
        state (cluster/read-state rendered state-output)]
    (with-params-check
      (cluster/adopt-state validate/spec rendered (:green/event opts) state))))

(defn data-fn [opts]
  (let [opts (ssh/with-machine-key opts)]
    (merge opts
         {:digitalocean_vpc_id (or (:vpc_id (:once/cluster opts)) fallback-vpc-id)
          :host-alias (utils/host-alias opts)
          ;; Only what a `build` genuinely knows: whether the package owns the
          ;; key, and where the local play should point the identity file.
          :ssh-keygen (validate/keygen? opts)
          :ssh-config-identity-file (ssh-config/identity-file opts)
          :kubernetes-minor (utils/kubernetes-minor (:kubernetes-version opts))
          :kubernetes-package-version
          (utils/kubernetes-package-version (:kubernetes-version opts))})))

(defn inventory
  "The remote play's inventory: the control plane and the workers, each
  node under its own name, from `nodes`."
  [opts]
  (let [opts (ssh/with-machine-key opts)
        nodes (nodes opts)
        ;; In keygen mode nothing guarantees an agent holds the generated key,
        ;; so the play is told which one to use; opt-out keeps the operator's
        ;; own arrangements, as it always did.
        host (fn [n] (cond-> {:ansible_host (:ip n) :ansible_user (:user n)
                              :private_ip (:vpc_ip n)}
                       (validate/keygen? opts)
                       (assoc :ansible_ssh_private_key_file (:ssh-private-key-path opts))))
        hosts (fn [role] (into (sorted-map)
                               (for [n nodes :when (= role (:role n))]
                                 [(:name n) (host n)])))]
    (json/generate-string
     {:all {:children
            {:control_plane {:hosts (hosts "control-plane")}
             :workers {:hosts (hosts "worker")}
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
                   :ip (entry-ip opts)
                   :user "root"
                   :block_state (if delete? "absent" "present")}}
     (ansible-local-specs opts))))

(defn ansible-remote-specs [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)]
    [(spec (template "ansible-remote" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-remote" "create.yml") (str dir "/create.yml") data)
     (spec (template "ansible-remote" "delete.yml") (str dir "/delete.yml") data)
     (spec (template "ansible-remote" "gitops.yml") (str dir "/gitops.yml") data)
     (raw-spec (str dir "/inventory.json") (inventory opts))]))

(defn ansible-remote-step
  "The remote play. On a delete it addresses the adopted cluster; a state
  that recorded no compute — the nodes are already gone — has nothing to
  clean up, and the step skips itself rather than render the fallbacks."
  [opts]
  (if (and (= :delete (:green/event opts))
           (nil? (:once/cluster opts)))
    opts
    (let [dir (tool-dir opts ansible-remote-tool)]
      (ansible/ansible-with-spec
       opts
       {:dir dir :inventory "inventory.json"
        :playbooks {:create "create.yml" :delete "delete.yml"}
        :host-key-checking false}
       (ansible-remote-specs opts)))))

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
