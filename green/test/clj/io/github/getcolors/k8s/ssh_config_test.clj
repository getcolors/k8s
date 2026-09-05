(ns io.github.getcolors.k8s.ssh-config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.k8s.ssh-config :as ssh-config]
            [io.github.getcolors.k8s.validate-test :as vt]))

(deftest the-alias-is-the-profile-and-the-identity-file-stays-unexpanded
  (is (= "k8s-test" (ssh-config/host-alias vt/base)))
  (is (= "~/.ssh/k8s-test" (ssh-config/identity-file vt/base))))

(deftest the-superseded-package-prefixed-block-is-still-ours-while-the-migration-is-in-flight
  (let [old ["# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK"
             "Host k8s-test" "  HostName 1.2.3.4"
             "# END k8s k8s-test ANSIBLE MANAGED BLOCK"]
        current ["# BEGIN k8s-test ANSIBLE MANAGED BLOCK"
                 "Host k8s-test" "  HostName 1.2.3.4"
                 "# END k8s-test ANSIBLE MANAGED BLOCK"]]
    (is (nil? (ssh-config/foreign-stanza-line old "k8s-test")))
    (is (nil? (ssh-config/foreign-stanza-line current "k8s-test")))
    (is (= 1 (ssh-config/foreign-stanza-line ["Host k8s-test" "  HostName 9.9.9.9"] "k8s-test")))))

(deftest a-global-option-above-the-first-host-blocks-the-run
  (is (= 1 (ssh-config/leading-option-line ["ServerAliveInterval 60" "Host x"])))
  (is (nil? (ssh-config/leading-option-line ["# a comment" "" "Host x" "  User root"]))))

(deftest the-refusal-is-reported-as-a-failed-step
  (let [home (str (java.nio.file.Files/createTempDirectory
                   "k8s-ssh-config" (into-array java.nio.file.attribute.FileAttribute [])))
        config (java.io.File. (str home "/.ssh/config"))]
    (.mkdirs (.getParentFile config))
    (spit config "Host k8s-test\n  HostName 9.9.9.9\n")
    (with-redefs [ssh-config/config-path (constantly config)]
      (let [refused (ssh-config/preflight! vt/base)]
        (is (= 1 (:green/exit refused)))
        (is (str/includes? (:green/err refused) "k8s-test"))))
    (testing "the package's own old block is not foreign"
      (spit config "# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK\nHost k8s-test\n  HostName 1.1.1.1\n# END k8s k8s-test ANSIBLE MANAGED BLOCK\n")
      (with-redefs [ssh-config/config-path (constantly config)]
        (is (= 0 (:green/exit (ssh-config/preflight! vt/base) 0)))))))
