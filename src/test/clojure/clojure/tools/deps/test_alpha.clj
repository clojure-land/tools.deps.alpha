(ns clojure.tools.deps.test-alpha
  (:require
    [clojure.test :refer [deftest is are testing]]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.extensions :as ext]
    [clojure.tools.deps.alpha.extensions.faken :as fkn]
    [clojure.tools.deps.alpha.util.dir :as dir]
    [clojure.tools.deps.alpha.util.maven :as mvn])
  (:import
    [java.io File]))

(deftest merge-alias-maps
  (are [m1 m2 out]
    (= out (#'deps/merge-alias-maps m1 m2))

    {} {} {}
    {} {:extra-deps {:a 1}} {:extra-deps {:a 1}}
    {:extra-deps {:a 1 :b 1}} {:extra-deps {:b 2}} {:extra-deps {:a 1 :b 2}}
    {} {:default-deps {:a 1}} {:default-deps {:a 1}}
    {:default-deps {:a 1 :b 1}} {:default-deps {:b 2}} {:default-deps {:a 1 :b 2}}
    {} {:override-deps {:a 1}} {:override-deps {:a 1}}
    {:override-deps {:a 1 :b 1}} {:override-deps {:b 2}} {:override-deps {:a 1 :b 2}}
    {} {:extra-paths ["a" "b"]} {:extra-paths ["a" "b"]}
    {:extra-paths ["a" "b"]} {:extra-paths ["c" "d"]} {:extra-paths ["a" "b" "c" "d"]}
    {} {:jvm-opts ["-Xms100m" "-Xmx200m"]} {:jvm-opts ["-Xms100m" "-Xmx200m"]}
    {:jvm-opts ["-Xms100m" "-Xmx200m"]} {:jvm-opts ["-Dfoo=bar"]} {:jvm-opts ["-Xms100m" "-Xmx200m" "-Dfoo=bar"]}
    {} {:main-opts ["foo.bar" "1"]} {:main-opts ["foo.bar" "1"]}
    {:main-opts ["foo.bar" "1"]} {:main-opts ["foo.baz" "2"]} {:main-opts ["foo.baz" "2"]}))

(def repo
  ;; "real"
  {'org.clojure/clojure {{:fkn/version "1.9.0"}
                         [['org.clojure/spec.alpha {:fkn/version "0.1.124"}]
                          ['org.clojure/core.specs.alpha {:fkn/version "0.1.10"}]]}
   'org.clojure/spec.alpha {{:fkn/version "0.1.124"} nil
                            {:fkn/version "0.1.1"} nil}
   'org.clojure/core.specs.alpha {{:fkn/version "0.1.10"} nil}

   ;; testing various scenarios
   'e1/a {{:fkn/version "1"} [['e1/b {:fkn/version "1"}]
                             ['e1/c {:fkn/version "2"}]]}
   'e1/b {{:fkn/version "1"} [['e1/c {:fkn/version "1"}]]}
   'e1/c {{:fkn/version "1"} nil
         {:fkn/version "2"} nil}})

(deftest test-basic-expand
  (fkn/with-libs repo
    (is (= (set (keys (deps/resolve-deps {:deps {'org.clojure/clojure {:fkn/version "1.9.0"}}} nil)))
          #{'org.clojure/clojure 'org.clojure/spec.alpha 'org.clojure/core.specs.alpha}))))

(deftest test-top-dominates
  (fkn/with-libs repo
    ;; dependent dep decides version
    (is (= (-> {:deps {'org.clojure/clojure {:fkn/version "1.9.0"}}}
             (deps/resolve-deps nil)
             (get 'org.clojure/spec.alpha)
             :fkn/version)
          "0.1.124"))

    ;; top dep wins
    (is (= (-> {:deps {'org.clojure/clojure {:fkn/version "1.9.0"}
                       'org.clojure/spec.alpha {:fkn/version "0.1.1"}}}
             (deps/resolve-deps nil)
             (get 'org.clojure/spec.alpha)
             :fkn/version)
          "0.1.1"))))

(deftest test-override-deps
  (fkn/with-libs repo
    ;; override dep wins
    (is (= (-> {:deps {'org.clojure/clojure {:fkn/version "1.9.0"}}}
             (deps/resolve-deps
               {:override-deps {'org.clojure/spec.alpha {:fkn/version "0.1.1"}}})
             (get 'org.clojure/spec.alpha)
             :fkn/version)
           "0.1.1"))))

(deftest test-default-deps
  (fkn/with-libs repo
    ;; default dep wins if none provided
    (is (= (-> {:deps {'org.clojure/clojure nil}}
               (deps/resolve-deps
                {:default-deps
                 {'org.clojure/clojure {:fkn/version "2.0.0"}}})
               (get 'org.clojure/clojure)
               :fkn/version)
           "2.0.0"))))

(defn libs->lib-ver
  [libmap]
  (reduce-kv
    (fn [lib-ver lib coord] (assoc lib-ver (-> lib name keyword) (:fkn/version coord)))
    {} libmap))

;; +a1 -> +b1 -> -c1
;;     -> +c2
(deftest test-dep-choice
  (fkn/with-libs repo
    (= (->> (deps/resolve-deps {:deps {'e1/a {:fkn/version "1"}}} nil) libs->lib-ver)
      {:a 1, :b 1, :c 2})))

;; -> +a1 -> +d1
;; -> +b1 -> -e1 -> -d2
;; -> +c1 -> +e2
(deftest test-dep-parent-missing
  (fkn/with-libs
    {'ex/a {{:fkn/version "1"} [['ex/d {:fkn/version "1"}]]}
     'ex/b {{:fkn/version "1"} [['ex/e {:fkn/version "1"}]]}
     'ex/c {{:fkn/version "1"} [['ex/e {:fkn/version "2"}]]}
     'ex/d {{:fkn/version "1"} nil
            {:fkn/version "2"} nil}
     'ex/e {{:fkn/version "1"} [['ex/d {:fkn/version "2"}]]
            {:fkn/version "2"} nil}}
    (let [r (->> (deps/resolve-deps {:deps {'ex/a {:fkn/version "1"}
                                            'ex/b {:fkn/version "1"}
                                            'ex/c {:fkn/version "1"}}} nil)
              libs->lib-ver)]
      (is (= r {:a "1", :b "1", :c "1", :d "1", :e "2"})))))

;; +a1 -> +b1 -> +x2 -> +y1
;; +c1 -> -x1 -> -z1
(deftest test-dep-choice2
  (fkn/with-libs
    {'ex/a {{:fkn/version "1"} [['ex/b {:fkn/version "1"}]]}
     'ex/b {{:fkn/version "1"} [['ex/x {:fkn/version "2"}]]}
     'ex/c {{:fkn/version "1"} [['ex/x {:fkn/version "1"}]]}
     'ex/x {{:fkn/version "2"} [['ex/y {:fkn/version "1"}]]
            {:fkn/version "1"} [['ex/z {:fkn/version "1"}]]}
     'ex/y {{:fkn/version "1"} nil}
     'ex/z {{:fkn/version "1"} nil}}
    (is (= {:a "1", :b "1", :c "1", :x "2", :y "1"}
          (let [res (deps/resolve-deps {:deps {'ex/a {:fkn/version "1"}, 'ex/c {:fkn/version "1"}}} nil)]
            (libs->lib-ver res))))))

;; c1 included via both a and b, with exclusions in one branch and without in the other
;; should always include d1
;; +a1 -> +c1 (excl d) -> d1
;; +b1 -> +c1 -> +d1
(deftest test-dep-same-version-different-exclusions
  (fkn/with-libs
    {'ex/a {{:fkn/version "1"} [['ex/c {:fkn/version "1" :exclusions ['ex/d]}]]}
     'ex/b {{:fkn/version "1"} [['ex/c {:fkn/version "1"}]]}
     'ex/c {{:fkn/version "1"} [['ex/d {:fkn/version "1"}]]}
     'ex/d {{:fkn/version "1"} nil}}
    (is (= {:a "1", :b "1", :c "1", :d "1"}
          (libs->lib-ver (deps/resolve-deps {:deps {'ex/a {:fkn/version "1"}, 'ex/b {:fkn/version "1"}}} nil))
          (libs->lib-ver (deps/resolve-deps {:deps {'ex/b {:fkn/version "1"}, 'ex/a {:fkn/version "1"}}} nil))))))

;; +a1 -> +b1 -> +c1 -> a1
;;     -> -c2 -> a1
(deftest test-circular-deps
  (fkn/with-libs {'ex/a {{:fkn/version "1"} [['ex/b {:fkn/version "1"}]
                                             ['ex/c {:fkn/version "2"}]]}
                  'ex/b {{:fkn/version "1"} [['ex/c {:fkn/version "1"}]]}
                  'ex/c {{:fkn/version "1"} [['ex/a {:fkn/version "1"}]]
                         {:fkn/version "2"} [['ex/a {:fkn/version "1"}]]}}
    (is (= {:a "1", :b "1", :c "2"}
           (let [res (deps/resolve-deps {:deps {'ex/a {:fkn/version "1"}}} nil)]
             (libs->lib-ver res))))))

(deftest test-local-root
  (let [base (.getCanonicalFile (File. "."))]
    (testing "a relative local root canonicalizes relative to parent dep"
      (binding [dir/*the-dir* base]
        (is (= ['ex/b {:local/root (.getPath (.getCanonicalFile (File. base "b")))}]
               (ext/canonicalize 'ex/b {:local/root "b"} {})))))
    (testing "an absolute local root canonicalizes to itself"
      (binding [dir/*the-dir* base]
        (is (= ['ex/b {:local/root "/b"}]
               (ext/canonicalize 'ex/b {:local/root "/b"} {})))))))

;; simple check that pom resolution is working - load tda itself as pom dep
(deftest test-local-pom
  (is (not (empty? (deps/resolve-deps
                     {:deps {'c/tda {:local/root "." :deps/manifest :pom}}
                      :mvn/repos mvn/standard-repos}
                     nil)))))


;[lib use-coord coord-id use-path include reason exclusions cut]
(deftest test-update-excl
  ;; new lib/version, no exclusions
  (let [ret (#'deps/update-excl 'a {:mvn/version "1"} {:mvn/version "1"} '[b a] true :new-version nil nil)]
    (is (= {:exclusions' nil, :cut' nil}
          (select-keys ret [:exclusions' :cut'])))
    (is (not (nil? (:child-pred ret)))))

  ;; new lib/version, with exclusions
  (let [ret (#'deps/update-excl 'a {:mvn/version "1" :exclusions ['c]} {:mvn/version "1"} '[b a] true :new-version nil nil)]
    (is (= {:exclusions' '{[b a] #{c}}, :cut' '{[a {:mvn/version "1"}] #{c}}}
          (select-keys ret [:exclusions' :cut'])))
    (is (not (nil? (:child-pred ret)))))

  ;; same lib/version, fewer excludes
  ;; a (excl c)
  ;; b -> a -> c1
  (let [excl '{[a] #{c}}
        ret (#'deps/update-excl 'a {:mvn/version "1"} {:mvn/version "1"} '[b a] false :same-version
              excl '{[a {:mvn/version "1"}] #{c}})]
    (is (= {:exclusions' excl, :cut' nil})) ;; remove cut
    (let [pred (:child-pred ret)]
      (is (true? (boolean (pred 'c))))))

  ;; same lib/version, subset excludes
  ;; a (excl c d)
  ;; b -> a (excl c)
  (let [excl '{[a] #{c d}}
        cut '{[a {:mvn/version "1"}] #{c d}}
        ret (#'deps/update-excl 'a '{:mvn/version "1" :exclusions [c]} {:mvn/version "1"} '[b a] false :same-version excl cut)]
    (is (= {:exclusions' '{[a] #{c d}, [b a] #{c d}}, :cut' '{[a {:mvn/version "1"}] #{c}}}))
    (let [pred (:child-pred ret)]
      (is (false? (boolean (pred 'c)))) ;; already enqueued
      (is (true? (boolean (pred 'd)))))) ;; newly enqueueable due to smaller exclusion set

  ;; same lib/version, same excludes
  ;; a (excl c)
  ;; b -> a (excl c)
  (let [excl '{[a] #{c}}
        cut '{[a {:mvn/version "1"}] #{c}}
        ret (#'deps/update-excl 'a {:mvn/version "1" :exclusions ['c]} {:mvn/version "1"} '[b a] false :same-version excl cut)]
    (is (= {:exclusions' excl, :cut' cut})) ;; no change
    (let [pred (:child-pred ret)]
      (is (false? (boolean (pred 'c))))))

  ;; same lib/version, more excludes
  ;; a (excl c)
  ;; b -> a (excl c, d)
  (let [excl '{[a] #{c}}
        cut '{[a {:mvn/version "1"}] #{c}}
        ret (#'deps/update-excl 'a '{:mvn/version "1" :exclusions [c d]} {:mvn/version "1"} '[b a] false :same-version excl cut)]
    (is (= {:exclusions' '{[a] #{c}, [b a] #{c d}}, :cut' cut})) ;; no change in cut
    (let [pred (:child-pred ret)] ;; everything already enqueued
      (is (false? (boolean (pred 'c))))
      (is (false? (boolean (pred 'd)))))))