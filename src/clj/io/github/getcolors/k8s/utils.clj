(ns io.github.getcolors.k8s.utils
  "Launcher contract and path/version helpers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def contract 2)

(defn tool-dir [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root (or (:profile opts) "k8s") tool))))

(defn unprefix-v [version] (str/replace-first (str version) #"^v" ""))
(defn kubernetes-minor [version]
  (let [[major minor] (str/split (unprefix-v version) #"\.")]
    (str "v" major "." minor)))
(defn kubernetes-package-version [version]
  (str (unprefix-v version) "-1.1"))
(defn host-alias [opts] (or (not-empty (str (:profile opts))) "k8s"))
