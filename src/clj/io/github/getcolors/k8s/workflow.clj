(ns io.github.getcolors.k8s.workflow
  "Two-node kubeadm lifecycle DAG and package-specific remote-state advice."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
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
   (let [opts (green-cli/read-pars (merge defaults opts) env)
         event (:green/event opts)
         real? (not (:green/dry-run opts))
         errors (vec
                 (concat
                  (validate/env-errors env)
                  (validate/state-errors opts)
                  (when (and real? (lifecycle-events event))
                    (validate/secret-errors opts))
                  (when (and real? (= :delete event)
                             (:compute-prevent-destroy opts))
                    [(str "compute destruction is protected; set "
                          (green-cli/par-name :compute-prevent-destroy)
                          "=false for this delete")])))]
     (if (seq errors)
       (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (assoc opts :green/exit 0)))))

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
  (let [dir-fn #(tools/tool-dir % tool)
        state-key #(str (:profile %) "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice
            dir-fn #(hash-map :bucket (:s3-bucket %)
                              :key (state-key %)
                              :region (:s3-region %)))
      "r2" (tofu/r2-backend-advice
            dir-fn #(hash-map :bucket (:r2-bucket %)
                              :key (state-key %)
                              :endpoint (:r2-endpoint %)))})))

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
