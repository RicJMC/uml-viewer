(ns uml-viewer.domain.policy
  (:require [clojure.string :as str]))

(defn- as-id [x]
  (keyword (name x)))

(defn- index-classes [graph]
  (into {} (map (juxt :id identity) (:classes graph))))

(defn- package-nses [pkg]
  (mapv as-id (:nses pkg)))

(defn assigned
  "Class ids listed in any package of `policy`."
  [policy]
  (set (mapcat package-nses (:packages policy))))

(defn- foreign-prefixes [policy]
  (mapv as-id (or (:foreign policy) [])))

(defn- matches-prefix? [id prefix]
  (let [s (name id)
        p (name prefix)]
    (or (= s p) (str/starts-with? s (str p ".")))))

(defn- id-list
  "One id or a seq of ids -> a vector of ids."
  [x]
  (cond
    (nil? x) []
    (sequential? x) (mapv as-id x)
    :else [(as-id x)]))

(defn normalize-rules
  "Explicit dependency rules from a policy or proposal map.

  :forbidden-pairs [{:from id-or-ids :to id-or-ids}]
    a :dependency from any listed source to any listed target is violating
    no matter what the ranks say.
  :independent [[id-or-ids ...] ...]
    any :dependency between two ids of the same group, in either direction,
    is violating.

  Ids match by dotted prefix (`store` covers `store.cache`). An edge whose
  two ends resolve to the same listed id (`store.cache -> store` under
  `store`) is that module's own wiring and never violates."
  [x]
  (let [pairs (->> (:forbidden-pairs x)
                   (keep (fn [p]
                           (when (map? p)
                             (let [from (id-list (:from p))
                                   to (id-list (:to p))]
                               (when (and (seq from) (seq to))
                                 {:from from :to to})))))
                   vec)
        groups (->> (:independent x)
                    (keep (fn [g]
                            (let [ids (id-list g)]
                              (when (seq ids) ids))))
                    vec)]
    {:forbidden-pairs pairs :independent groups}))

(defn- listed? [id ids]
  (boolean (some #(matches-prefix? (as-id id) %) ids)))

(defn- distinct-listed-pair?
  "True when `from-id` matches listed id `a` and `to-id` matches a different
   listed id `b`. Both ends resolving to the same listed id is one module's
   internal wiring (e.g. `store.cache -> store` under `store`), not a pair."
  [from-id to-id as bs]
  (boolean
    (some (fn [a]
            (and (listed? from-id [a])
                 (some #(and (not= % a) (listed? to-id [%])) bs)))
          as)))

(defn- forbidden-pair? [e rules]
  (boolean
    (some (fn [{:keys [from to]}]
            (distinct-listed-pair? (:from e) (:to e) from to))
          (:forbidden-pairs rules))))

(defn- independent-pair? [e rules]
  (boolean
    (some (fn [group]
            (distinct-listed-pair? (:from e) (:to e) group group))
          (:independent rules))))

(defn omitted-id?
  "True when `id` is listed in `omit` or sits under a listed component."
  [id omit]
  (boolean (some #(matches-prefix? (as-id id) (as-id %)) omit)))

(defn- collapse-id [id prefixes]
  (->> prefixes
       (filter #(matches-prefix? id %))
       (sort-by (comp - count name))
       first))

(defn hierarchical?
  "True when policy describes a namespace tree instead of invented packages."
  [policy]
  (or (true? (:hierarchical policy))
      (and (nil? (:packages policy)) (nil? (:diagrams policy)))))

(defn unassigned
  "Scanned project classes that no package lists. Foreign classes are omitted.
  Hierarchical policies place every project ns in the tree."
  [policy graph]
  (if (hierarchical? policy)
    []
    (->> (:classes graph)
         (remove :foreign)
         (remove #(contains? (assigned policy) (:id %)))
         (sort-by (comp name :id))
         vec)))

(defn- lookup-class [idx id]
  (get idx (as-id id)))

(defn kind-rank [k]
  (get {:implements 4 :inheritance 4 :composition 3 :aggregation 2
        :association 1 :dependency 0} k 0))

(def proposal-notice
  "PROPOSAL — not instantiated in code")

(defn- nse-entry
  "A layer :nses item: a namespace id, or a nested group map."
  [x]
  (if (map? x)
    (let [id (as-id (or (:id x) (:label x)))
          label (or (:label x) (name id))]
      {:id id :label label :nses (mapv nse-entry (or (:nses x) []))})
    (as-id x)))

(defn nse-ids
  "Leaf namespace ids from a layer :nses vector, flattening nested groups."
  [nses]
  (into []
        (mapcat (fn [x]
                  (if (map? x)
                    (nse-ids (or (:nses x) []))
                    [(as-id x)]))
                nses)))

(defn- layer-from
  [i layer]
  (when (map? layer)
    (let [nses (mapv nse-entry (or (:nses layer) []))
          id (as-id (or (:id layer) (:label layer) (str "layer-" i)))
          label (or (:label layer) (name id))]
      {:id id :label label :nses nses})))

(defn normalize-proposal
  "Named layers that are not namespace segments. Nil when absent."
  [p]
  (cond
    (nil? p) nil
    (sequential? p)
    (let [layers (vec (keep-indexed layer-from p))]
      (when (seq layers)
        {:notice proposal-notice :layers layers}))
    (map? p)
    (let [layers (vec (keep-indexed layer-from (or (:layers p) [])))
          rules (normalize-rules p)]
      (when (seq layers)
        {:notice (or (:notice p) proposal-notice)
         :layers layers
         :omit (mapv as-id (or (:omit p) []))
         :forbidden-pairs (:forbidden-pairs rules)
         :independent (:independent rules)}))
    :else nil))

(defn timestamp-name
  "Default name for a new proposal."
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
           (java.util.Date.)))

(defn- named-proposal-from
  [i p]
  (when (map? p)
    (let [layers (or (:layers (normalize-proposal p)) [])
          rules (normalize-rules p)
          id (as-id (or (:id p) (str "proposal-" i)))
          n (or (:name p) (:label p) (name id))]
      {:id id
       :name (str n)
       :layers layers
       :omit (mapv as-id (or (:omit p) []))
       :forbidden-pairs (:forbidden-pairs rules)
       :independent (:independent rules)
       :notice (or (:notice p) proposal-notice)})))

(defn named-proposals
  "Named proposal maps `{:id :name :layers}` from a policy or IR doc."
  [x]
  (let [xs (:proposals x)
        one (:proposal x)]
    (cond
      (sequential? xs)
      (vec (keep-indexed named-proposal-from xs))

      (and (map? one) (or (:name one) (seq (:layers one))))
      (let [p (named-proposal-from 0 (if (:layers one) one {:layers (:layers one)}))]
        (if p [p] []))

      (sequential? one)
      (let [n (normalize-proposal one)]
        (if n
          [{:id :proposal :name "Proposal" :layers (:layers n) :notice (:notice n)}]
          []))

      :else [])))

(defn proposal-by-id
  [x id]
  (when id
    (some #(when (= (as-id id) (:id %)) %) (named-proposals x))))

(defn proposal-layers
  "Normalized proposal layer maps, or []."
  [x]
  (or (:layers (first (named-proposals x)))
      (:layers (normalize-proposal (if (and (map? x) (contains? x :proposal))
                                     (:proposal x)
                                     x)))
      []))

(defn- group-nses [group]
  (cond
    (map? group) (mapv as-id (or (:nses group) []))
    (sequential? group) (mapv as-id group)
    :else []))

(defn level-groups
  "Rank groups as vectors of top-level segments. :levels wins; else :proposal."
  [policy]
  (if (seq (:levels policy))
    (mapv group-nses (:levels policy))
    (mapv #(nse-ids (:nses %)) (proposal-layers policy))))

(defn level-ranks
  "Top-level segment -> rank. Smaller is higher-level (inner)."
  [policy]
  (into {}
        (mapcat (fn [rank group]
                  (map (fn [seg] [(as-id seg) rank]) group))
                (range)
                (level-groups policy))))

(defn ranks-from-layers
  "Inner-first proposal layers -> rank map. Same contract as `level-ranks`."
  [layers]
  (level-ranks {:levels (mapv (fn [layer]
                                (if (map? layer)
                                  (nse-ids (or (:nses layer) []))
                                  layer))
                              (or layers []))}))

(defn- top-seg [id]
  (keyword (first (str/split (name id) #"\."))))

(defn rank-of
  "Rank for `id`: exact key in `ranks`, else longest dotted prefix."
  [id ranks]
  (let [id (as-id id)
        parts (str/split (name id) #"\.")]
    (some (fn [n]
            (get ranks (keyword (str/join "." (take n parts)))))
          (range (count parts) 0 -1))))

(defn- with-levels [classes ranks]
  (mapv (fn [c]
          (if-let [lv (and (not (:foreign c))
                           (rank-of (:id c) ranks))]
            (assoc c :level lv)
            c))
        classes))

(defn violation-kind
  "Why a :dependency breaks the dependency rule: `:forbidden` (an explicit
  forbidden pair in the edge's direction), `:independent` (two ids of one
  independent group), or `:ranks` (it ascends the ranks, from a higher-level
  inner segment to a lower-level outer one). Nil when it does not violate.
  Explicit rules outrank the rank rule; the rank rule needs both ends ranked
  and allows same rank."
  ([e ranks] (violation-kind e ranks nil))
  ([e ranks rules]
   (when (= :dependency (:kind e))
     (cond
       (forbidden-pair? e rules) :forbidden
       (independent-pair? e rules) :independent
       (let [rf (rank-of (:from e) ranks)
             rt (rank-of (:to e) ranks)]
         (and rf rt (< rf rt))) :ranks))))

(defn violating-dependency?
  "True when a :dependency breaks the dependency rule; see `violation-kind`
  for the three ways it can."
  ([e ranks] (violating-dependency? e ranks nil))
  ([e ranks rules]
   (boolean (violation-kind e ranks rules))))

(defn mark-violations
  "Set `:violating` on dependency edges that break the dependency rule."
  ([edges ranks] (mark-violations edges ranks nil))
  ([edges ranks rules]
   (mapv (fn [e]
           (if (violating-dependency? e ranks rules)
             (assoc e :violating true)
             (dissoc e :violating)))
         edges)))

(defn restamp-ranks
  "Recompute `:level` on classes and `:violating` on edges from `ranks`.
  Used when a proposal's layer order differs from document `:levels`."
  ([classes edges ranks] (restamp-ranks classes edges ranks nil))
  ([classes edges ranks rules]
   {:classes (with-levels (mapv #(dissoc % :level) classes) ranks)
    :edges (mark-violations edges ranks rules)}))

(defn merge-edges
  "Keep the strongest edge for each [from to] pair.
  A surviving :dependency is violating if any bundled edge was."
  [edges]
  (->> edges
       (group-by (juxt :from :to))
       vals
       (mapv (fn [es]
               (let [best (apply max-key #(kind-rank (:kind %)) es)]
                 (cond-> (dissoc best :violating)
                   (and (= :dependency (:kind best))
                        (some :violating es))
                   (assoc :violating true)))))))

(defn collapse-graph
  "Rewrite foreign classes to policy `:foreign` prefixes. Unlisted externals drop."
  [policy graph]
  (let [prefixes (foreign-prefixes policy)
        remap (into {}
                    (keep (fn [c]
                            (when (:foreign c)
                              (when-let [p (collapse-id (:id c) prefixes)]
                                [(:id c) p])))
                          (:classes graph)))
        project (vec (remove :foreign (:classes graph)))
        foreigns (->> (vals remap)
                      distinct
                      (sort-by name)
                      (mapv (fn [id] {:id id :name (name id) :foreign true})))
        classes (into project foreigns)
        ids (set (map :id classes))
        edges (->> (:edges graph)
                   (map (fn [e]
                          (assoc e
                            :from (get remap (:from e) (:from e))
                            :to (get remap (:to e) (:to e)))))
                   (filter #(and (ids (:from %)) (ids (:to %))))
                   (remove #(= (:from %) (:to %)))
                   merge-edges)]
    {:classes classes :edges edges}))

(defn apply-edge-kinds
  [edges kinds omit]
  (let [kinds (or kinds {})
        omit (set (map (fn [p] (mapv as-id p)) (or omit [])))]
    (->> edges
         (remove #(contains? omit [(:from %) (:to %)]))
         (mapv (fn [e]
                 (let [e (if-let [k (get kinds [(:from e) (:to e)])]
                           (assoc e :kind k)
                           e)]
                   (cond-> e
                     (not= :dependency (:kind e)) (dissoc :violating))))))))

(defn- apply-kinds [edges diagram]
  (apply-edge-kinds edges (:edge-kinds diagram) (:omit-edges diagram)))

(defn- edges-among
  ([graph ids diagram]
   (edges-among graph ids diagram nil))
  ([graph ids diagram home]
   (let [idset (set ids)
         homes (set home)]
     (-> (->> (:edges graph)
              (filter #(and (idset (:from %)) (idset (:to %))))
              (filter #(or (nil? home)
                           (homes (:from %))
                           (homes (:to %))
                           (= :implements (:kind %)))))
         merge-edges
         (apply-kinds diagram)))))

(defn- ir-class [c hide?]
  (cond-> {:id (:id c) :name (:name c)}
    (:ns c) (assoc :ns (:ns c))
    (:stereotype c) (assoc :stereotype (:stereotype c))
    (:foreign c) (assoc :shape :oval)
    (and hide? (not (:foreign c))) (assoc :hide-members true)))

(defn- visible-class [idx id hide?]
  (when-let [c (lookup-class idx id)]
    (ir-class c hide?)))

(defn- lookup [graph id]
  (first (filter #(= id (:id %)) (:classes graph))))

(defn- foreigns-from [graph from-ids]
  (let [from (set from-ids)
        wanted (set (for [e (:edges graph)
                          :when (from (:from e))
                          :let [c (lookup graph (:to e))]
                          :when (:foreign c)]
                      (:id c)))]
    (->> (:classes graph)
         (filter :foreign)
         (filter #(wanted (:id %)))
         (sort-by (comp name :id))
         vec)))

(defn- neighbors [graph home-ids]
  (let [homes (set home-ids)]
    (vec (distinct
           (for [e (:edges graph)
                 :when (homes (:from e))
                 :when (not (homes (:to e)))]
             (:to e))))))

(defn- overview-diagram [policy graph diagram]
  (let [idx (index-classes graph)
        hide? (boolean (:hide-members diagram))
        pkgs (mapv (fn [p]
                     {:id (as-id (:id p))
                      :label (:label p)
                      :classes (into []
                                     (keep #(visible-class idx % hide?)
                                           (package-nses p)))})
                   (:packages policy))
        extra (unassigned policy graph)
        pkgs (cond-> pkgs
               (seq extra)
               (conj {:id :unassigned
                      :label "Unassigned"
                      :classes (mapv #(ir-class % hide?) extra)}))
        pkg-ids (mapcat (fn [p] (map :id (:classes p))) pkgs)
        foreign (foreigns-from graph pkg-ids)
        ids (concat pkg-ids (map :id foreign))]
    (cond-> {:title (:title diagram)
             :direction (keyword (or (:direction diagram) :tb))
             :packages pkgs
             :edges (edges-among graph ids diagram)}
      (seq foreign) (assoc :foreign (mapv #(ir-class % false) foreign)))))

(defn- find-package [policy id]
  (let [want (as-id id)]
    (first (filter #(= want (as-id (:id %))) (:packages policy)))))

(defn- package-diagram [policy graph diagram]
  (let [pkg (find-package policy (:package diagram))]
    (when-not pkg
      (throw (ex-info (str "diagram package not in policy: " (:package diagram))
                      {:diagram diagram})))
    (let [idx (index-classes graph)
          home (package-nses pkg)
          nbrs (neighbors graph home)
          stubs (remove #(:foreign (lookup graph %)) nbrs)
          foreign (foreigns-from graph home)
          hide-home? (boolean (:hide-members diagram))
          classes (into []
                        (concat
                          (keep #(visible-class idx % hide-home?) home)
                          (keep #(visible-class idx % true) stubs)))
          ids (concat (map :id classes) (map :id foreign))]
      (cond-> {:title (:title diagram)
               :direction (keyword (or (:direction diagram) :lr))
               :packages [{:id (as-id (:id pkg))
                           :label (:label pkg)
                           :classes classes}]
               :edges (edges-among graph ids diagram home)}
        (seq foreign) (assoc :foreign (mapv #(ir-class % false) foreign))))))

(defn apply-policy
  "Turn a scanned graph and a policy into an IR document."
  [policy graph]
  (let [proposals (named-proposals policy)
        proposal (or (normalize-proposal (first proposals))
                     (normalize-proposal (:proposal policy)))
        rules (normalize-rules policy)
        ranks (level-ranks policy)
        graph (-> (collapse-graph policy graph)
                  (update :edges mark-violations ranks rules)
                  (update :classes with-levels ranks))
        levels (level-groups policy)]
    (if (hierarchical? policy)
      (cond-> {:title (or (:title policy) "UML")
               :hierarchical true
               :prefix (or (:prefix policy) "uml-viewer")
               :order (mapv as-id (or (:order policy) []))
               :levels levels
               :edge-kinds (or (:edge-kinds policy) {})
               :omit-edges (or (:omit-edges policy) [])
               :omit (mapv as-id (or (:omit policy) []))
               :classes (:classes graph)
               :edges (:edges graph)}
        (seq proposals) (assoc :proposals proposals)
        proposal (assoc :proposal proposal)
        (seq (:forbidden-pairs rules))
        (assoc :forbidden-pairs (:forbidden-pairs rules))
        (seq (:independent rules))
        (assoc :independent (:independent rules)))
      {:title (or (:title policy) "UML")
       :diagrams (mapv (fn [d]
                         (if (= :overview (:view d))
                           (overview-diagram policy graph d)
                           (package-diagram policy graph d)))
                       (:diagrams policy))})))
