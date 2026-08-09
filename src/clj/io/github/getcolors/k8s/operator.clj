(ns io.github.getcolors.k8s.operator
  "kubectl dispatch through the managed control-plane SSH alias."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.k8s.utils :as utils]
            [io.github.getcolors.k8s.validate :as validate]))

(def shell-quote process/posix-quote)

(defn command [opts args]
  (let [remote (str/join " "
                         (map shell-quote
                              (concat ["sudo" "-n" "kubectl"
                                       "--kubeconfig=/etc/kubernetes/admin.conf"]
                                      args)))]
    ["ssh" "-F" (str (io/file (System/getProperty "user.home") ".ssh/config"))
     "--" (utils/host-alias opts) remote]))

(def inherit-run process/run-inherit)

(defn run
  ([state-file _kind args] (run state-file :kubectl args inherit-run (System/getenv)))
  ([state-file _kind args runner env]
   (try
     (let [file (io/file state-file)]
       (if-not (.exists file)
         {:green/exit 2 :green/err (str "desired state file not found: " file)}
         (let [opts (-> (green-cli/read-state file (slurp file))
                        (assoc :green/state-file (.getAbsolutePath file))
                        (green-cli/read-pars env))
               errors (validate/env-errors env)]
           (if (seq errors)
             {:green/exit 2 :green/err (str/join "\n" errors)}
             (let [{:keys [exit err]} (runner (command opts args))]
               (cond-> {:green/exit (if (zero? exit) 0 (max 1 exit))}
                 (and (not (zero? exit)) (not-empty err))
                 (assoc :green/err err)))))))
     (catch Throwable t
       {:green/exit 2 :green/err (or (ex-message t) (str (class t)))}))))
