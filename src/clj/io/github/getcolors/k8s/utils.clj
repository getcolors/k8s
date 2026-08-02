(ns io.github.getcolors.k8s.utils
  "Launcher contract and pure naming/version helpers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def contract 1)

(defn tool-dir [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root (or (:profile opts) "k8s") tool))))

(defn unprefix-v [version] (str/replace-first (str version) #"^v" ""))

(defn chart-versions
  "Helm package versions, deliberately separate from component app versions.
  ExternalDNS is the non-identity mapping: chart 1.21.1 packages app 0.21.0."
  [opts]
  {:cilium-chart (unprefix-v (:cilium-version opts))
   :hcloud-ccm-chart (unprefix-v (:hcloud-cloud-controller-manager-version opts))
   :hcloud-csi-chart (unprefix-v (:hcloud-csi-driver-version opts))
   :external-dns-chart "1.21.1"
   :cert-manager-chart (:cert-manager-version opts)})
