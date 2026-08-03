(ns io.github.getcolors.k8s.operator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.k8s.operator :as operator]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k8s-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(deftest command-uses-ssh-and-remote-admin-kubeconfig
  (let [command (operator/command {:profile "k8s-digitalocean"} ["get" "nodes"])]
    (is (= "ssh" (first command)))
    (is (= "-F" (second command)))
    (is (str/ends-with? (nth command 2) "/.ssh/config"))
    (is (= ["--" "k8s-digitalocean"
            "'sudo' '-n' 'kubectl' '--kubeconfig=/etc/kubernetes/admin.conf' 'get' 'nodes'"]
           (subvec command 3)))))

(deftest arguments-are-shell-quoted
  (is (str/includes? (last (operator/command {:profile "p"}
                                              ["get" "pods; id"]))
                     "'pods; id'")))

(deftest run-refuses-profile-overlay
  (let [file (str (temp-dir) "/colors.yml")]
    (spit file "profile: demo\n")
    (let [result (operator/run file :kubectl [] (fn [_] {:exit 0})
                               {"COLORS_PAR_PROFILE" "other"})]
      (is (= 2 (:green/exit result))))))
