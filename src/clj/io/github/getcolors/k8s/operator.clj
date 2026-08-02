(ns io.github.getcolors.k8s.operator
  "kubectl/talosctl dispatch with short-lived 0600 credentials from Tofu state."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.k8s.utils :as utils]
            [io.github.getcolors.k8s.validate :as validate])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- process-env [opts]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [value (not-empty (str (get opts k)))]
                   [env-var value])))
         (validate/tofu-env opts :provider-backend))))

(defn- tofu-output [opts name]
  (let [dir (utils/tool-dir opts "k8s-infrastructure")
        env (process-env opts)
        init (process/run ["tofu" (str "-chdir=" dir) "init"
                           "-input=false" "-no-color"] {:extra-env env})]
    (when-not (zero? (:exit init))
      (throw (ex-info (str "tofu init failed while loading cluster credentials: "
                           (or (not-empty (:err init)) (not-empty (:out init))))
                      {:green/exit (:exit init)})))
    (let [result (process/run ["tofu" (str "-chdir=" dir) "output" "-raw" name]
                              {:extra-env env})]
      (if (zero? (:exit result))
        (:out result)
        (throw (ex-info (str "tofu output " name " failed: "
                             (or (not-empty (:err result)) "(no output)"))
                        {:green/exit (:exit result)}))))))

(defn cluster-configs [opts]
  (let [outputs (:k8s/outputs opts)]
    {:kubeconfig (or (:kubeconfig outputs) (tofu-output opts "kubeconfig"))
     :talosconfig (or (:talosconfig outputs) (tofu-output opts "talosconfig"))}))

(defn- chmod [^java.io.File file mode]
  (try
    (Files/setPosixFilePermissions (.toPath file)
                                   (PosixFilePermissions/fromString mode))
    (catch UnsupportedOperationException _
      (.setReadable file false false)
      (.setWritable file false false)
      (.setExecutable file false false)
      (.setReadable file true true)
      (.setWritable file true true)
      (when (str/includes? mode "x")
        (.setExecutable file true true)))))

(defn- delete-tree! [^java.io.File root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file true))))

(defn with-cluster-configs
  "Call f with KUBECONFIG/TALOSCONFIG paths and erase both in finally."
  [opts f]
  (let [dir (.toFile (Files/createTempDirectory "colors-k8s-" (make-array java.nio.file.attribute.FileAttribute 0)))
        kube (io/file dir "kubeconfig")
        talos (io/file dir "talosconfig")]
    (try
      (chmod dir "rwx------")
      (let [{:keys [kubeconfig talosconfig]} (cluster-configs opts)]
        (spit kube kubeconfig)
        (spit talos talosconfig)
        (chmod kube "rw-------")
        (chmod talos "rw-------")
        (f {"KUBECONFIG" (.getAbsolutePath kube)
            "TALOSCONFIG" (.getAbsolutePath talos)}))
      (finally (delete-tree! dir)))))

(defn inherit-run [argv extra-env]
  (try
    (let [builder (ProcessBuilder. ^java.util.List (mapv str argv))]
      (.putAll (.environment builder) (into {} extra-env))
      (let [child (-> builder .inheritIO .start)]
        {:exit (.waitFor child)}))
    (catch Exception e
      {:exit -1 :err (or (.getMessage e) (str (class e)))})))

(defn command [kind args]
  (into [(case kind :kubectl "kubectl" :talosctl "talosctl")] args))

(defn run
  "Read desired state and run an operator CLI with temporary credentials."
  ([state-file kind args] (run state-file kind args inherit-run (System/getenv)))
  ([state-file kind args runner env]
   (try
     (let [file (io/file state-file)]
       (if-not (.exists file)
         {:green/exit 2 :green/err (str "desired state file not found: " file)}
         (let [opts (-> (green-cli/read-state file (slurp file))
                        (assoc :green/state-file (.getAbsolutePath file))
                        (green-cli/read-pars env))
               errors (vec (concat (validate/env-errors env)
                                   (validate/state-errors opts)
                                   (validate/secret-errors opts [:provider-backend])))]
           (if (seq errors)
             {:green/exit 2 :green/err (str/join "\n" errors)}
             (with-cluster-configs
               opts
               (fn [config-env]
                 (let [{:keys [exit err]} (runner (command kind args) config-env)]
                   (cond-> {:green/exit (if (zero? exit) 0 (max 1 exit))}
                     (and (not (zero? exit)) (not-empty err))
                     (assoc :green/err err)))))))))
     (catch Throwable t
       {:green/exit (or (:green/exit (ex-data t)) 2)
        :green/err (or (ex-message t) (str (class t)))}))))
