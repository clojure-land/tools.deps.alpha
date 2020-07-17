;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.deps.alpha.util.concurrent :as concurrent]
    [clojure.tools.deps.alpha.util.dir :as dir]
    [clojure.tools.deps.alpha.extensions :as ext]

    ;; Load extensions
    [clojure.tools.deps.alpha.extensions.maven]
    [clojure.tools.deps.alpha.extensions.local]
    [clojure.tools.deps.alpha.extensions.git]
    [clojure.tools.deps.alpha.extensions.deps]
    [clojure.tools.deps.alpha.extensions.pom])
  (:import
    [clojure.lang PersistentQueue]
    [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private merge-alias-rules
  {:deps merge
   :extra-deps merge
   :override-deps merge
   :default-deps merge
   :classpath-overrides merge
   :paths (comp vec distinct concat)
   :extra-paths (comp vec distinct concat)
   :jvm-opts (comp vec concat)
   :main-opts (comp last #(remove nil? %) vector)})

(defn- choose-rule [alias-key]
  (or (merge-alias-rules alias-key)
    (throw (ex-info (format "Unknown alias key: %s" alias-key) {:key alias-key}))))

(defn- merge-alias-maps
  "Like merge-with, but using custom per-alias-key merge function"
  [& ms]
  (reduce
    #(reduce
       (fn [m [k v]] (update m k (choose-rule k) v))
       %1 %2)
    {} ms))

(defn combine-aliases
  "Find, read, and combine alias maps identified by alias keywords from
  a deps configuration into a single args map."
  [deps alias-kws]
  (->> alias-kws
    (map #(get-in deps [:aliases %]))
    (apply merge-alias-maps)))

(defn lib-location
  "Find the file path location of where a lib/coord would be located if procured
  without actually doing the procuring!"
  [lib coord deps-config]
  (ext/lib-location lib coord deps-config))

(defn- canonicalize-deps
  [deps config]
  (reduce
    (fn [m [lib coord]] (conj m (ext/canonicalize lib coord config)))
    [] deps))

;; exclusions tree

(defn- excluded?
  [exclusions path lib]
  (let [lib-name (first (str/split (name lib) #"\$"))
        base-lib (symbol (namespace lib) lib-name)]
    (loop [search path]
      (when (seq search)
        (if (get-in exclusions [search base-lib])
          true
          (recur (pop search)))))))

(defn- update-excl
  "Update exclusions and cut based on whether this is a new lib/version,
  a new instance of an existing lib/version, or not including."
  [lib use-coord coord-id use-path include reason exclusions cut]
  (let [coord-excl (when-let [e (:exclusions use-coord)] (set e))]
    (cond
      ;; if adding new lib/version, include all non-excluded children
      include
      (if (nil? coord-excl)
        {:exclusions' exclusions, :cut' cut, :child-pred (constantly true)}
        {:exclusions' (assoc exclusions use-path coord-excl)
         :cut' (assoc cut [lib coord-id] coord-excl)
         :child-pred (fn [lib] (not (contains? coord-excl lib)))})

      ;; if seeing same lib/ver again, narrow exclusions to intersection of prior and new.
      ;; only include new unexcluded children (old excl set minus new excl set)
      ;; as others were already enqueued when first added
      (= reason :same-version)
      (let [exclusions' (if (seq coord-excl) (assoc exclusions use-path coord-excl) exclusions)
            cut-coord (get cut [lib coord-id]) ;; previously cut from this lib, so were not enqueued
            new-cut (set/intersection coord-excl cut-coord)
            enq-only (set/difference cut-coord new-cut)]
        {:exclusions' exclusions'
         :cut' (assoc cut [lib coord-id] new-cut)
         :child-pred (set enq-only)})

      :else ;; otherwise, no change
      {:exclusions' exclusions, :cut' cut})))

;; version map

;; {lib {:versions {coord-id coord}     ;; all version coords
;;       :paths    {coord-id #{paths}}  ;; paths to coord-ids
;;       :select   coord-id             ;; current selection
;;       :top      true}                ;; if selection is top dep

(defn- add-version
  "Add a new version of a lib to the version map"
  [vmap lib coord path coord-id]
  (-> (or vmap {})
    (assoc-in [lib :versions coord-id] coord)
    (update-in [lib :paths]
      (fn [coord-paths]
        (merge-with into {coord-id #{path}} coord-paths)))))

(defn- select-version
  "Mark a particular coord as selected in version map"
  [vmap lib coord-id top?]
  (update-in vmap [lib] merge (cond-> {:select coord-id}
                                top? (assoc :top true))))

(defn- selected-version
  "Get currently selected version of lib"
  [vmap lib]
  (get-in vmap [lib :select]))

(defn- selected-coord
  "Get currently selected coord of lib"
  [vmap lib]
  (get-in vmap [lib :versions (selected-version vmap lib)]))

(defn- parent-missing?
  "Is parent path now missing from the selected lib/versions?
  This can happen if a newer version is found, orphaning enqueued children."
  [vmap path]
  (when (seq path)
    (let [parent-lib (last path)
          parent-path (vec (butlast path))
          {:keys [paths select]} (get vmap parent-lib)]
      (not (contains? (get paths select) parent-path)))))

(defn- dominates?
  "Is new-coord newer than old-coord?"
  [lib new-coord old-coord config]
  (pos? (ext/compare-versions lib new-coord old-coord config)))

(defn- include-coord?
  "This is the key decision-making function when considering a lib/coord node in
  the traversal graph. It returns :include (whether to include this lib/coord), a
  :reason why it was included or not, and an updated :vmap (may have new version added
  and/or new selected version for a lib)"
  [vmap lib coord coord-id path exclusions config]
  (cond
    ;; lib is a top dep and this is it => select
    (empty? path)
    {:include true, :reason :new-top-dep,
     :vmap (-> vmap
             (add-version lib coord path coord-id)
             (select-version lib coord-id true))}

    ;; lib is excluded in this path => omit
    (excluded? exclusions path lib)
    {:include false, :reason :excluded, :vmap vmap}

    ;; lib is a top dep and this isn't it => omit
    (get-in vmap [lib :top])
    {:include false, :reason :use-top, :vmap vmap}

    ;; lib's parent path is not included => omit
    (parent-missing? vmap path)
    {:include false, :reason :parent-omitted, :vmap vmap}

    ;; new lib => select
    (not (contains? vmap lib))
    {:include true, :reason :new-dep,
     :vmap (-> vmap
             (add-version lib coord path coord-id)
             (select-version lib coord-id false))}

    ;; existing lib, same version => omit (but update vmap, may need to enqueue newly unexcluded children)
    (= coord-id (selected-version vmap lib))
    {:include false, :reason :same-version, :vmap (add-version vmap lib coord path coord-id)}

    ;; existing lib, newer version => select
    (dominates? lib coord (selected-coord vmap lib) config)
    {:include true, :reason :newer-version,
     :vmap (-> vmap
             (add-version lib coord path coord-id)
             (select-version lib coord-id false))}

    ;; existing lib, older version => omit
    :else
    {:include false, :reason :older-version, :vmap vmap}))

;; expand-deps

(defn- trace+
  "Add an entry to the trace if needed"
  [trace? trace parents lib coord use-coord coord-id override-coord include reason]
  (when trace?
    (let [entry (cond-> {:path parents, :lib lib, :coord coord, :use-coord use-coord, :coord-id coord-id
                         :include include, :reason reason}
                  override-coord (assoc :override-coord override-coord))]
      (conj trace entry))))

(defn- next-path
  [pendq q on-error]
  (let [[fchild & rchildren] pendq]
    (if fchild
      {:path fchild, :pendq rchildren, :q' q}
      (let [next-q (peek q)
            q' (pop q)]
        (if (map? next-q)
          (let [{:keys [pend-children ppath child-pred]} next-q
                result @pend-children]
            (when (instance? Throwable result)
              (on-error result))
            (next-path (->> result (filter (fn [[lib _coord]] (child-pred lib))) (map #(conj ppath %))) q' on-error))
          {:path next-q, :q' q'})))))

(defn- expand-deps
  "Dep tree expansion, returns version map"
  [deps default-deps override-deps config executor trace?]
  (letfn [(err-handler [throwable]
            (do
              (concurrent/shutdown-on-error executor)
              (throw ^Throwable throwable)))
          (children-task [lib use-coord use-path child-pred]
            {:pend-children
             (let [{:deps/keys [manifest root]} use-coord]
               (dir/with-dir (if root (jio/file root) dir/*the-dir*)
                 (concurrent/submit-task executor
                   #(try
                      (canonicalize-deps (ext/coord-deps lib use-coord manifest config) config)
                      (catch Throwable t t)))))
             :ppath use-path
             :child-pred child-pred})]
    (loop [pendq nil ;; a resolved child-lookup thunk to look at first
           q (into (PersistentQueue/EMPTY) (map vector deps)) ;; queue of nodes or child-lookups
           version-map nil ;; track all seen versions of libs and which version is selected
           exclusions nil ;; tracks exclusions marked in the tree
           cut nil ;; tracks cuts made of child nodes based on exclusions
           trace []] ;; trace expansion
      (let [{:keys [path pendq q']} (next-path pendq q err-handler)]
        (if path
          (let [[lib coord] (peek path)
                parents (pop path)
                use-path (conj parents lib)
                override-coord (get override-deps lib)
                choose-coord (cond override-coord override-coord
                                   coord coord
                                   :else (get default-deps lib))
                use-coord (merge choose-coord (ext/manifest-type lib choose-coord config))
                coord-id (ext/dep-id lib use-coord config)
                {:keys [include reason vmap]} (include-coord? version-map lib use-coord coord-id parents exclusions config)
                {:keys [exclusions' cut' child-pred]} (update-excl lib use-coord coord-id use-path include reason exclusions cut)
                new-q (if child-pred (conj q' (children-task lib use-coord use-path child-pred)) q')]
            (recur pendq new-q vmap exclusions' cut'
              (trace+ trace? trace parents lib coord use-coord coord-id override-coord include reason)))
          (cond-> version-map trace? (with-meta {:trace {:log trace, :vmap version-map, :exclusions exclusions}})))))))

(defn- lib-paths
  [version-map]
  (reduce
    (fn [ret [lib {:keys [select versions paths]}]]
      (let [coord (get versions select)
            paths (->> (get paths select) (map last) (remove nil?) vec)]
        (assoc ret lib (cond-> coord (seq paths) (assoc :dependents paths)))))
    {} version-map))

(defn- download-libs
  [executor lib-map config]
  (let [lib-futs (reduce-kv
                   (fn [fs lib coord]
                     (let [fut (concurrent/submit-task
                                 executor
                                 #(try
                                    (ext/coord-paths lib coord (:deps/manifest coord) config)
                                    (catch Throwable t t)))]
                       (assoc fs lib fut)))
                   {} lib-map)]
    (reduce-kv (fn [lm lib fut]
                 (let [result @fut]
                   (if (instance? Throwable result)
                     (do
                       (concurrent/shutdown-on-error executor)
                       (throw ^Throwable result))
                     (assoc-in lm [lib :paths] result))))
      lib-map lib-futs)))

(defn resolve-deps
  "Takes a deps configuration map and resolves the transitive dependency graph
  from the initial set of deps. args-map is a map with several keys (all
  optional) that can modify the results of the transitive expansion:

    :extra-deps - a map from lib to coord of deps to add to the main deps
    :override-deps - a map from lib to coord of coord to use instead of those in the graph
    :default-deps - a map from lib to coord of deps to use if no coord specified

  settings is an optional map of settings:
    :trace - boolean. If true, the returned lib map will have metadata with :trace log
    :threads - int. If provided, sets the number of concurrent download threads

  Returns a lib map (map of lib to coordinate chosen)."
  ([deps-map args-map]
    (resolve-deps deps-map args-map nil))
  ([deps-map args-map settings]
   (let [{:keys [extra-deps default-deps override-deps]} args-map
         n (if-let [threads (:threads settings)]
             (Long/parseLong threads)
             concurrent/processors)
         executor (concurrent/new-executor n)
         deps (merge (:deps deps-map) extra-deps)
         version-map (-> deps
                       (canonicalize-deps deps-map)
                       (expand-deps default-deps override-deps deps-map executor (:trace settings)))
         lib-map (lib-paths version-map)
         lib-map' (download-libs executor lib-map deps-map)]
     (with-meta lib-map' (meta version-map)))))

(defn- make-tree
  [lib-map]
  (let [{roots false, nonroots true} (group-by #(-> % val :dependents boolean) lib-map)]
    (loop [q (into (PersistentQueue/EMPTY) roots)
           remaining nonroots
           tree {}]
      (let [[lib coord :as node] (peek q)
            q' (pop q)]
        (if node
          (let [{children true, not-used false} (group-by #(-> % val :dependents set (contains? lib)) remaining)]
            (recur (into q' children) not-used (assoc tree lib (assoc coord :children (map key children)))))
          tree)))))

(defn print-tree
  "Print lib-map tree to the console"
  [lib-map]
  (let [tree (make-tree lib-map)]
    (letfn [(print-node [lib indent]
              (let [{:keys [children] :as coord} (get tree lib)]
                (println (str indent (ext/coord-summary lib coord)))
                (doseq [child-lib children]
                  (print-node child-lib (str indent "  ")))))]
      (doseq [[lib coord] tree :when (-> coord :dependents nil?)]
        (print-node lib "")))))

(defn- make-classpath-roots
  "Takes a lib map, and a set of explicit paths. Extracts the paths for each chosen
  lib coordinate, and assembles a classpath string using the system path separator.
  The classpath-args is a map with keys that can be used to modify the classpath
  building operation:

    :extra-paths - extra classpath paths to add to the classpath
    :classpath-overrides - a map of lib to path, where path is used instead of the coord's paths

  Returns the classpath as a vector of string paths."
  [lib-map paths {:keys [classpath-overrides extra-paths] :as classpath-args}]
  (let [libs (merge-with (fn [coord path] (assoc coord :paths [path])) lib-map classpath-overrides)
        lib-paths (mapcat :paths (vals libs))]
    (remove str/blank? (concat extra-paths paths lib-paths))))

(defn- join-classpath
  "Takes a coll of string classpath roots and creates a platform sensitive classpath"
  [roots]
  (str/join File/pathSeparator roots))

(defn make-classpath
  "Takes a lib map, and a set of explicit paths. Extracts the paths for each chosen
  lib coordinate, and assembles a classpath string using the system path separator.
  The classpath-args is a map with keys that can be used to modify the classpath
  building operation:

    :extra-paths - extra classpath paths to add to the classpath
    :classpath-overrides - a map of lib to path, where path is used instead of the coord's paths

  Returns the classpath as a string."
  [lib-map paths classpath-args]
  (-> (make-classpath-roots lib-map paths classpath-args) join-classpath))

(comment
  (require '[clojure.tools.deps.alpha.util.maven :as mvn])

  (def ex-svc (concurrent/new-executor 2))

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.apache.xmlgraphics/batik-transcoder {:mvn/version "1.7"}}
               nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}
                'org.clojure/core.memoize {:mvn/version "0.5.8"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/clojure {:mvn/version "1.9.0"}
                'org.clojure/clojurescript {:mvn/version "1.9.946"}
                'reagent/reagent {:mvn/version "0.6.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/core.async {:mvn/version "0.3.426"}
                'reagent/reagent {:mvn/version "0.6.0"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (expand-deps {'org.clojure/tools.deps.alpha {:mvn/version "0.4.277"}}
    nil nil {:mvn/repos mvn/standard-repos} ex-svc true)

  (print-tree
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.8.0"}
                          'org.clojure/core.memoize {:mvn/version "0.5.8"}}
                   :mvn/repos mvn/standard-repos} nil))

  (make-classpath
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}
                          'org.clojure/core.memoize {:mvn/version "0.5.8"}}
                   :mvn/repos mvn/standard-repos} nil) ["src"] {:extra-paths ["test"]})

  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/tools.analyzer.jvm {:mvn/version "0.6.9"}}
                   :mvn/repos mvn/standard-repos} nil))

  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire {:mvn/version "5.7.0"}}
                   :mvn/repos mvn/standard-repos} nil))

  ;; top deps win
  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.2.0"}
                          'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos} nil))

  ;; deps replacement
  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos}
      {:deps {'org.clojure/tools.gitlibs {:mvn/version "0.2.64"}}}))

  ;; deps addition
  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire/cheshire {:mvn/version "5.8.0"}}
                   :mvn/repos mvn/standard-repos}
      {:extra-deps {'org.clojure/tools.gitlibs {:mvn/version "0.2.64"}}}))

  ;; override-deps
  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/core.memoize {:mvn/version "0.5.8"}}
       :mvn/repos mvn/standard-repos}
      {:override-deps {'org.clojure/clojure {:mvn/version "1.3.0"}}})
    ["src"] nil)

  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/tools.deps.alpha {:mvn/version "0.1.40"}
              'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}}
       :mvn/repos mvn/standard-repos} nil) nil nil)

  ;; extra paths
  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/clojure {:mvn/version "1.10.0"}}
       :mvn/repos mvn/standard-repos} nil)
    ["src"]
    {:extra-paths ["extra"]})

  ;; classpath overrides
  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/tools.deps.alpha {:mvn/version "0.1.40"}
              'org.clojure/clojure {:mvn/version "1.9.0-alpha17"}}
       :mvn/repos mvn/standard-repos} nil) nil
    '{:classpath-overrides {org.clojure/clojure "foo"}})

  (resolve-deps
    {:deps '{org.clojure/clojure {:mvn/version "1.9.0"}
             org.clojure/clojurescript {:mvn/version "1.9.946"}
             reagent {:mvn/version "0.6.0"}}
     :mvn/repos mvn/standard-repos}
    nil)

  ;; err case
  (resolve-deps {:deps {'bogus {:mvn/version "1.2.3"}}
                 :mvn/repos mvn/standard-repos} nil)

  (resolve-deps {:deps {'bogus "1.2.3"}
                 :mvn/repos mvn/standard-repos} nil)

  (require
    '[clojure.tools.deps.alpha.extensions.git]
    '[clojure.tools.deps.alpha.extensions.deps])

  (resolve-deps
    {:deps {'foo {:git/url "https://github.com/clojure/core.async.git"
                  :sha "ecea2539a724a415b15e50f12815b4ab115cfd35"}}}
    nil)

  (require '[clojure.tools.deps.alpha.util.session :as session])
  (time
    (do
      (session/with-session
        (resolve-deps
          {:deps {'com.google.cloud/google-cloud-monitoring {:mvn/version "1.78.0"}}
           :mvn/repos (merge mvn/standard-repos
                        {"datomic-cloud" {:url "s3://datomic-releases-1fc2183a.s3-us-east-1.amazonaws.com/maven/releases"}})}
          nil))
      nil))

  )