(ns io.github.getcolors.k8s.operator-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.k8s.operator :as operator]))

(deftest commands-do-not-use-a-shell
  (is (= ["kubectl" "get" "pods; touch /tmp/no"]
         (operator/command :kubectl ["get" "pods; touch /tmp/no"])))
  (is (= ["talosctl" "get" "members"]
         (operator/command :talosctl ["get" "members"]))))

(deftest configs-are-temporary-and-private
  (let [paths (atom nil)
        result (operator/with-cluster-configs
                {:k8s/outputs {:kubeconfig "kube-secret"
                               :talosconfig "talos-secret"}}
                (fn [env]
                  (reset! paths env)
                  (doseq [path (vals env)]
                    (is (.exists (io/file path)))
                    (is (not (.canExecute (io/file path)))))
                  {:green/exit 0}))]
    (is (= 0 (:green/exit result)))
    (doseq [path (vals @paths)]
      (is (not (.exists (io/file path)))))))
