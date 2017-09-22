(ns clojure.tools.deps.alpha.specs
  (:require [clojure.spec.alpha :as s]))

;; library, like org.clojure/clojure
(s/def ::lib symbol?)

;; coordinates

(s/def ::path string?)

(s/def :mvn/version string?)
(s/def ::exclusions (s/coll-of ::lib :kind set? :into #{}))
(s/def :mvn/coord (s/keys :req [:mvn/version] :opt-un [::path ::exclusions]))

(s/def :local/root string?)
(s/def :local/coord (s/keys :req-un [:local/root] :opt-un [::path]))

(s/def ::coord (s/or :mvn :mvn/coord
                     :local :local/coord))

;; resolve-deps args
;;   used to modify the expanded deps tree
;;   ::extra-deps - map of lib to coordinate added to the initial deps collection
;;   ::override-deps - map of lib to coordinate to use instead of the coord found during expansion
;;   ::default-deps - map of lib to coordinate to use if no coord is specified in extension
(s/def ::resolve-args (s/keys :opt-un [::extra-deps ::override-deps ::default-deps]))
(s/def ::extra-deps (s/map-of ::lib ::coord))
(s/def ::override-deps (s/map-of ::lib ::coord))
(s/def ::default-deps (s/map-of ::lib ::coord))

;; make-classpath args
;;   used when constructing the classpath
;;   ::classpath-overrides - map of lib to path to use instead of the artifact found during resolution
;;   ::extra-paths - collection of extra paths to add to the classpath in addition to ::paths
(s/def ::classpath-args (s/keys :opt-un [::classpath-overrides ::extra-deps]))
(s/def ::classpath-overrides (s/map-of ::lib ::path))
(s/def ::extra-paths (s/coll-of string? :kind vector? :into []))

;; deps map (format of the deps.edn file)
;;   ::deps - a map of library to coordinate (which has a provider type)
;;   ::providers - a map of artifact provider type to provider configuration
;;   ::resolve-args - a map with the same structure as the resolve-deps second arg
;;   ::aliases - a map from keyword (the alias) to a resolve-args map OR a make-classpath overrides map
(s/def ::paths (s/coll-of string? :kind vector? :into []))
(s/def ::deps (s/map-of ::lib ::coord))
(s/def ::alias keyword?)
(s/def ::aliases (s/map-of ::alias (s/or ::resolve-args ::classpath-args)))
(s/def ::deps-map (s/keys :opt-un [::paths ::deps ::aliases]))

;; lib map
;;   a map of lib to resolved coordinate (a coord with a ::path) and dependent info
(s/def ::dependents (s/coll-of ::lib))
(s/def ::resolved-coord (s/merge ::coord (s/keys :req-un [::path] :opt-un [::dependents])))
(s/def ::lib-map (s/map-of ::lib ::resolved-coord))

;; Providers

;; maven provider
(s/def :mvn/repos (s/map-of ::repo-id ::repo))
(s/def ::repo-id string?)
(s/def ::repo (s/keys :opt-un [::url]))
(s/def ::url string?)
(s/def :mvn/local-repo string?)

;; API

(s/fdef clojure.tools.deps.alpha/resolve-deps
  :args (s/cat :deps ::deps-map :options ::resolve-args)
  :ret ::lib-map)

(s/fdef clojure.tools.deps.alpha/make-classpath
  :args (s/cat :libs ::lib-map :paths ::paths :classpath-args ::classpath-args)
  :ret string?)

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument (stest/enumerate-namespace 'clojure.tools.deps.alpha))
  )
