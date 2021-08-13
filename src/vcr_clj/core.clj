(ns vcr-clj.core
  (:require [vcr-clj.cassettes :refer [cassette-exists?
                                       read-cassette
                                       write-cassette]]))

(defn ^:private var-name
  [var]
  (let [{:keys [ns name]} (meta var)]
    (str ns "/" name)))

(defn ^:private add-meta-from
  "Returns a version of x1 with its metadata set to the metadata
   of x2."
  [x1 x2]
  (with-meta x1 (meta x2)))

(def ^{:dynamic true :private true} *recording?*
  ;; defaulting this to true so that calls on other threads can be
  ;; recorded, e.g. when running a web server whose handler makes
  ;; calls that ought to be recorded.
  true)

(defn ^:private validate-specs
  [alleged-specs]
  (when-not (and (coll? alleged-specs)
                 (every? map? alleged-specs))
    (throw (ex-info "vcr-clj expected a collection of specs, but instead got this"
                    {:not-specs alleged-specs}))))

(defn ^:private build-wrapped-fn
  [record-fn {:keys [var arg-transformer arg-key-fn recordable? return-transformer]
              :or {arg-transformer vector
                   arg-key-fn vector
                   recordable? (constantly true)
                   return-transformer identity}}]
  (let [orig-fn (deref var)
        the-var-name (var-name var)
        wrapped (fn [& args]
                  (let [args* (apply arg-transformer args)]
                    (if-not (and *recording?* (apply recordable? args*))
                      (apply orig-fn args*)
                      (let [res (binding [*recording?* false]
                                  (return-transformer (apply orig-fn args*)))
                            call {:var-name the-var-name
                                  :arg-key (apply arg-key-fn args*)
                                  :return res}]
                        (record-fn call)
                        res))))]
    (add-meta-from wrapped orig-fn)))

(def ^:private states (atom {}))

(defn cassette-state
  "While in the body of a with-cassette call, returns either
  :recording or :replaying as appropriate. Otherwise returns nil."
  []
  (let [the-states (distinct (vals @states))]
    (cond
      (empty? the-states) nil
      (= 1 (count the-states)) (first the-states)
      :else (throw (IllegalStateException. "vcr-clj is in multiple states -- are you running several tests concurrently?")))))

(defmacro ^:private with-state
  [state & body]
  `(let [o# (Object.)]
     (swap! states assoc o# ~state)
     (try
       ~@body
       (finally
         (swap! states dissoc o#)))))

;; TODO: add the ability to configure whether out-of-order
;; calls are allowed, or repeat calls, or such and such.
(defn record
  "Redefs the vars to record the calls, and returns [val cassette]
   where val is the return value of func."
  ([specs func]
   (record specs func {:calls []}))
  ([specs func {:keys [calls]}]
   (let [recorded-at (java.util.Date.)
         calls (atom calls)
         record! #(swap! calls conj %)
         redeffings (->> specs
                         (map (juxt :var (partial build-wrapped-fn record!)))
                         (into {}))
         func-return (binding [*recording?* true]
                       (with-state :recording
                         (with-redefs-fn redeffings func)))
         cassette {:calls @calls :recorded-at recorded-at}]
     [func-return cassette])))

;; I guess currently we aren't recording actual arguments, just the arg-key.
;; Should that change?
(defn playbacker
  "Given a cassette, returns a stateful function which, when called with
   the var name and the arguments key, either throws an exception if the
   ordering has been violated or returns the return value for that call.

   order-scope can be:
     :global   all requests must come in the same order they were recorded in
     :var      all requests to the same function must come in the same order
     :key      requests can come in (more or less) any order, as ordering is
               only scoped to the arg key"
  [cassette order-scope]
  ;; don't support anything else yet
  (case order-scope
    :key (let [calls (atom (group-by (juxt :var-name :arg-key) (:calls cassette)))]
           (fn [var-name arg-key]
             (let [next-val (swap! calls
                                   (fn [x]
                                     (let [v (first (get x [var-name arg-key]))]
                                       (with-meta
                                         (update-in x [[var-name arg-key]] rest)
                                         {:v v}))))]
               (or (:v (meta next-val))
                   (throw (ex-info (format "No more recorded calls to %s!"
                                           var-name)
                                   {:function var-name
                                    :arg-key arg-key}))))))))

;; Assuming that order is only preserved for calls to any var in
;; particular, not necessarily all the vars considered together.
(defn playback
  [specs cassette func]
  (let [the-playbacker (playbacker cassette :key)
        redeffings
        (into {}
              (for [{:keys [var arg-transformer arg-key-fn recordable?]
                     :or {arg-transformer vector
                          arg-key-fn vector
                          recordable? (constantly true)}}
                    specs
                    :let [orig (deref var)
                          the-var-name (var-name var)
                          wrapped (fn [& args]
                                    (let [args* (apply arg-transformer args)]
                                      (if (apply recordable? args*)
                                        (let [k (apply arg-key-fn args*)]
                                          (:return (the-playbacker the-var-name k)))
                                        (apply orig args*))))]]
                [var (add-meta-from wrapped orig)]))]
    (with-state :replaying
      (with-redefs-fn redeffings func))))

(def ^:dynamic *verbose?* false)
(defn println'
  [& args]
  (when *verbose?* (apply println args)))

(defn with-cassette-fn*
  [{:keys [name serialization accumulate?] :as cassette-data} specs func]
  (validate-specs specs)
  (let [cassette-name (or name cassette-data)]
    (when-not (or (string? cassette-name) (keyword? cassette-name))
      (throw (ex-info "No valid cassette name given" {:invalid cassette-name})))
    (if (cassette-exists? cassette-name)
      (if accumulate?
        (let [cassette (read-cassette cassette-name serialization)]
          (println' "Recording accumulating" cassette-name "cassette...")
          (let [[return cassette] (record specs func cassette)]
            (println' "Serializing...")
            (write-cassette cassette-name cassette serialization)
            return))
        (do
          (println' "Running with existing" cassette-name "cassette...")
          (playback specs (read-cassette cassette-name serialization) func)))
      (do
        (println' "Recording new" cassette-name "cassette...")
        (let [[return cassette] (record specs func)]
          (println' "Serializing...")
          (write-cassette cassette-name cassette serialization)
          return)))))

(defmacro with-cassette
  "Runs the given body with a cassette defined by the given cassette data and
  a list of specs.

  Cassette data can be either the full name of the cassette or a map defining
  the cassette data.
  {
   :name           a required key specifying the full name of the cassette.
   :serialization  optional map defining serialization settings for the cassette
       :print-handlers a function that overrides the built-in function at
                       `vcr-clj.cassettes.serialization/default-print-handlers`.
                       The function determines how complex Java objects are
                       saved in the cassette. See the puget docs on type extensions
                       for more details.
       :data-readers   map that merges over the defaults at
                       `vcr-clj.cassettes.serialization/data-readers`.
                       This mapping determines how the serialized Java objects in
                       the saved cassette are converted back to the original Java
                       objects. See Clojure's EDN docs for more details.
  }

  Each spec is:
    {
     :var a var

     ;; the rest are optional

     :arg-transformer
                  a function with the same arg signature as the var,
                  which is expected to return a vector of equivalent
                  arguments. During recording/playback, the original
                  arguments to the function call are passed through this
                  transformer, and the transformed arguments are passed
                  to arg-key-fn, recordable? and the recorded function.
                  Defaults to clojure.core/vector.
     :arg-key-fn  a function of the same arguments as the var that is
                  expected to return a value that can be stored and
                  compared for equality to the expected call. Defaults
                  to clojure.core/vector.
     :recordable? a predicate with the same arg signature as the var.
                  If the predicate returns false/nil on any call, the
                  call will be passed through transparently to the
                  original function without recording/playback.
     :return-transformer
                  a function that the return value will be passed through
                  while recording, which can be useful for doing things
                  like ensuring serializability.
    }"
  {:style/indent 2}
  [cdata specs & body]
  `(with-cassette-fn* ~cdata ~specs (fn [] ~@body)))
