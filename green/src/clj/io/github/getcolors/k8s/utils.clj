(ns io.github.getcolors.k8s.utils
  "Launcher contract and path/version helpers."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]))

(def contract 2)

(defn tool-dir [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "k8s"}))

(defn unprefix-v [version] (str/replace-first (str version) #"^v" ""))
(defn kubernetes-minor [version]
  (let [[major minor] (str/split (unprefix-v version) #"\.")]
    (str "v" major "." minor)))
(defn kubernetes-package-version [version]
  (str (unprefix-v version) "-1.1"))
(defn host-alias [opts] (or (not-empty (str (:profile opts))) "k8s"))
