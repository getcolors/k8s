(ns io.github.getcolors.k8s.ssh-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.k8s.ssh :as ssh]
            [io.github.getcolors.k8s.validate :as validate]
            [io.github.getcolors.k8s.validate-test :as vt]))

(deftest keygen-mode-is-the-absence-of-a-supplied-key
  (is (validate/keygen? vt/base))
  (is (not (validate/keygen? vt/optout))))

(deftest a-build-never-names-the-operators-home
  ;; Committed goldens must mean the same thing on every workstation, so a
  ;; build renders a fixed placeholder rather than reading ~/.ssh.
  (let [opts (ssh/with-machine-key (assoc vt/base :green/event :build))]
    (is (= "/home/build-placeholder/.ssh/k8s-test" (:ssh-private-key-path opts)))
    (is (= "/home/build-placeholder/.ssh/k8s-test.pub" (:ssh-public-key-path opts)))
    (testing "the placeholder lands on the provider's own machine-key key"
      (is (= "/home/build-placeholder/.ssh/k8s-test.pub" (:digitalocean-ssh-keys opts))))
    (is (not (re-find #"build-placeholder" (str (System/getenv "HOME")))))))

(deftest a-dry-run-is-held-to-the-same-rule-as-a-build
  (is (ssh/rendered-only? {:green/event :build}))
  (is (ssh/rendered-only? {:green/event :create :green/dry-run true}))
  (is (not (ssh/rendered-only? {:green/event :create}))))

(deftest real-events-render-the-real-path
  (let [opts (ssh/with-machine-key (assoc vt/base :green/event :create))]
    (is (not (re-find #"build-placeholder" (:ssh-private-key-path opts))))
    (is (.endsWith ^String (:ssh-private-key-path opts) "/.ssh/k8s-test"))))

(deftest opt-out-opts-pass-through-untouched
  (let [opts (assoc vt/optout :green/event :build)]
    (is (= opts (ssh/with-machine-key opts)))))
