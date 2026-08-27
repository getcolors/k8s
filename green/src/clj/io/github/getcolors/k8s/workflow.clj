(ns io.github.getcolors.k8s.workflow
  "Two-node kubeadm lifecycle DAG and package-specific remote-state advice."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.k8s.tools :as tools]
            [io.github.getcolors.k8s.validate :as validate]))

(def defaults
  {:compute-prevent-destroy true
   :provider-compute "digitalocean"
   :provider-dns "no-infra"
   :provider-backend "local"
   :kubernetes-distribution "kubeadm"
   :kubernetes-cni "flannel"
   :control-plane-count 1
   :worker-count 1
   :digitalocean-cloud-controller true
   :repository-branch "main"
   :repository-path "./clusters/k8s-digitalocean"
   :cert-manager-acme-environment "production"
   :workdir ".colors"})
(def lifecycle-events #{:create :delete})

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (lifecycle-events event)) (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false for this delete")]))]}
    env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :k8s/start [start-step :k8s/load-infrastructure]
      :k8s/load-infrastructure [tools/load-infrastructure-step :k8s/ansible-remote]
      :k8s/ansible-remote [tools/ansible-remote-step :k8s/ansible-local]
      :k8s/ansible-local [tools/ansible-local-step :k8s/infrastructure]
      :k8s/infrastructure [tools/infrastructure-step :k8s/generated-cleanup]
      :k8s/generated-cleanup [tools/generated-cleanup-step])
    (case step
      :k8s/start [start-step :k8s/infrastructure]
      :k8s/infrastructure [tools/infrastructure-step :k8s/ansible-local]
      :k8s/ansible-local [tools/ansible-local-step :k8s/ansible-remote]
      :k8s/ansible-remote [tools/ansible-remote-step :k8s/acceptance]
      :k8s/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting-steps
  [:k8s/load-infrastructure :k8s/infrastructure :k8s/ansible-local
   :k8s/ansible-remote :k8s/acceptance :k8s/generated-cleanup])

(def workflow
  (-> (wf/workflow {:start :k8s/start :wire-fn wire-fn})
      (wf/advice-add :k8s/load-infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :k8s/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      progress/advise
      (dry-run/advise side-effecting-steps)))
