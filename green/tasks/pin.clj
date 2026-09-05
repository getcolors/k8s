(ns pin (:require [clojure.java.shell :as sh] [clojure.string :as str]))
;; One SHA, three payloads. Every payload is born unpinned — no invented SHAs —
;; and `bb pin` stamps or re-stamps it after a clean, pushed HEAD. Each site
;; recognises exactly two forms, its unpinned birth shape and its pinned shape,
;; and the run fails loudly when a payload matches neither.
;;
;; K8s's launchers pin only k8s. `green` and `once` come transitively from
;; green/deps.edn, the red and blue SDK pins ride inside each payload beside
;; the package pin, and the red payload's ONCE pin is stamped by hand when the
;; ONCE pin moves, so this task rewrites one site per colour.
(defn git [& args] (let [{:keys [exit out]} (apply sh/sh "git" args)] (when (zero? exit) (str/trim out))))

(defn stamp-green [s sha]
  (when (re-find #"\(def \^:private k8s-sha (?:nil|\"[0-9a-f]{40}\")\)" s)
    (str/replace-first s #"\(def \^:private k8s-sha (?:nil|\"[0-9a-f]{40}\")\)"
                       (str "(def ^:private k8s-sha \"" sha "\")"))))

(defn stamp-red [s sha]
  (let [pinned (str "\"package-k8s-red\": \"github:getcolors/k8s#" sha "\",")]
    (cond (str/includes? s "\"package-k8s-red\": null,")
          (str/replace-first s "\"package-k8s-red\": null," pinned)
          (re-find #"\"package-k8s-red\": \"github:getcolors/k8s#[0-9a-f]{40}\"," s)
          (str/replace-first s #"\"package-k8s-red\": \"github:getcolors/k8s#[0-9a-f]{40}\"," pinned))))

(def blue-unpinned-meta "# dependencies = []\n# ///")
(defn blue-pinned-meta [sha]
  (str "# dependencies = [\"package-k8s-blue\", \"blue\"]\n"
       "#\n"
       "# [tool.uv.sources]\n"
       "# package-k8s-blue = { git = \"https://github.com/getcolors/k8s.git\", rev = \"" sha "\", subdirectory = \"blue\" }\n"
       "# blue = { git = \"https://github.com/getcolors/blue.git\", rev = \"290f313ead5ca162875c33a049c880da017eae09\" }\n"
       "#\n"
       ;; package-once-blue carries its own, older blue pin; the override makes
       ;; this package's blue pin win, as it does in blue/pyproject.toml.
       "# [tool.uv]\n"
       "# override-dependencies = [\"blue @ git+https://github.com/getcolors/blue.git@290f313ead5ca162875c33a049c880da017eae09\"]\n"
       "# ///"))
(defn stamp-blue [s sha]
  ;; First stamp is structural: the metadata block gains its git sources and the
  ;; UNPINNED paragraph collapses to a pinned-state note. Re-pinning is a SHA swap.
  (cond (str/includes? s blue-unpinned-meta)
        (-> s
            (str/replace-first blue-unpinned-meta (blue-pinned-meta sha))
            (str/replace-first #"(?s)# UNPINNED:.*?K8S_LIB_ROOT=/path/to/k8s\n"
                               "# Stamped by `bb pin`. K8S_LIB_ROOT=/path/to/k8s still overrides the\n# pin with a working tree.\n"))
        (re-find #"k8s\.git\", rev = \"[0-9a-f]{40}\"" s)
        (str/replace-first s #"k8s\.git\", rev = \"[0-9a-f]{40}\""
                           (str "k8s.git\", rev = \"" sha "\""))))

(def sites
  [{:path "../skills/package-k8s-green/green" :stamp stamp-green}
   {:path "../skills/package-k8s-red/red" :stamp stamp-red}
   {:path "../skills/package-k8s-blue/blue" :stamp stamp-blue}])

(let [dirty (git "status" "--porcelain") sha (git "rev-parse" "HEAD") remotes (git "branch" "-r" "--contains" sha)]
  (cond (seq dirty) (do (binding [*out* *err*] (println "k8s working tree is dirty; commit before pinning")) (System/exit 2))
        (not (str/includes? (str remotes) "origin/")) (do (binding [*out* *err*] (println "k8s HEAD is not pushed")) (System/exit 2))
        :else (let [errors (atom [])]
                (doseq [{:keys [path stamp]} sites]
                  (let [s (slurp path) n (stamp s sha)]
                    (if n (spit path n) (swap! errors conj (str "could not locate a pin form in " path)))))
                (if (seq @errors)
                  (do (binding [*out* *err*] (println (str/join "\n" @errors))) (System/exit 2))
                  (println "pinned 3 launchers to" (subs sha 0 7))))))
