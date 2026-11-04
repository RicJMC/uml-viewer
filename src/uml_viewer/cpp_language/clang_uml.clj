(ns uml-viewer.cpp-language.clang-uml
  "Adapter for the JSON class-diagram generator in clang-uml.
  This namespace maps the generator's declared entities and relations; it does
  not parse C++ or infer UML relations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- viewer-id [qualified-name]
  (keyword (str/replace qualified-name "::" ".")))

(defn- qualified-name [element]
  (or (:display_name element)
      (if-let [namespace (:namespace element)]
        (str namespace "::" (:name element))
        (:name element))))

(defn- diagnostic [code entity message]
  {:severity :warning :code code :entity entity :message message})

(defn- absolute-root [source-root]
  (.getPath (.getCanonicalFile (io/file source-root))))

(defn- source-ident
  [root location name qualified overload-id]
  (when (and (map? location)
             (string? (:file location))
             (number? (:line location))
             (pos? (:line location)))
    (cond-> {:lang :cpp
             :root root
             :file (:file location)
             :line (:line location)
             :name name
             :qualified-name qualified}
      (number? (:column location)) (assoc :column (:column location))
      overload-id (assoc :overload-id overload-id))))

(defn- param-signature [parameter]
  (str (or (:type parameter) "?")
       (when (seq (:name parameter)) (str " " (:name parameter)))))

(defn- overload-id [qualified method parameter-types location index]
  (str qualified "::" method "(" (str/join "," parameter-types) ")@"
       (or (:file location) "?") ":" (or (:line location) index) ":"
       (or (:column location) index)))

(defn- convert-method [root class-name index method]
  (let [name (:name method)
        params (vec (or (:parameters method) []))
        signature (mapv param-signature params)
        loc (:source_location method)
        oid (overload-id class-name name (mapv #(or (:type %) "?") params)
                         loc index)
        ident (source-ident root loc name class-name oid)
        text (str name "(" (str/join ", " signature) ")"
                  (when (seq (:type method)) (str " : " (:type method))))]
    (cond-> {:name name
             :signature (str/join ", " signature)
             :overload-id oid
             :text text}
      (= "private" (:access method)) (assoc :private true)
      ident (assoc :source-ident ident))))

(defn- convert-field [root class-name field]
  (let [name (:name field)
        loc (:source_location field)
        ident (source-ident root loc name class-name nil)]
    (cond-> {:name name
             :text (str name (when (seq (:type field)) (str " : " (:type field))))}
      (= "private" (:access field)) (assoc :private true)
      ident (assoc :source-ident ident))))

(defn- convert-class [root element]
  (let [qualified (qualified-name element)
        id (viewer-id qualified)
        location (:source_location element)
        ident (source-ident root location (:name element) qualified nil)
        namespace (:namespace element)]
    (cond-> {:id id
             :name (:name element)
             :namespace namespace
             :qualified-name qualified
             :clang-uml-id (str (:id element))
             :language :cpp
             :metrics-status :unavailable
             :ops (->> (:methods element)
                       (map-indexed (partial convert-method root qualified))
                       vec)
             :fields (mapv #(convert-field root qualified %) (:members element))}
      namespace (assoc :ns namespace)
      ident (assoc :source-ident ident))))

(defn- relationship-kind [type]
  (case type
    "extension" :inheritance
    "inheritance" :inheritance
    "dependency" :dependency
    "association" :association
    "aggregation" :aggregation
    nil))

(defn convert
  "Convert a parsed clang-uml JSON class diagram map to viewer hierarchical IR.

  `source-root` is the root clang-uml used for relative source_location paths.
  Class IDs are fully namespace-qualified viewer IDs. Original clang-uml IDs
  are retained and used to resolve relationships without guessing endpoints."
  [diagram source-root]
  (let [root (absolute-root source-root)
        elements (vec (or (:elements diagram) []))
        classes (filterv #(= "class" (:type %)) elements)
        unsupported-elements (filterv #(not= "class" (:type %)) elements)
        by-clang-id (into {} (map (fn [element]
                                    [(str (:id element)) element]) classes))
        converted (mapv #(convert-class root %) classes)
        duplicate-clang-id (some (fn [[id n]] (when (> n 1) id))
                                 (frequencies (map #(str (:id %)) classes)))
        duplicate-id (some (fn [[id n]] (when (> n 1) id))
                           (frequencies (map :id converted)))]
    (when duplicate-clang-id
      (throw (ex-info "clang-uml emitted duplicate class IDs"
                      {:clang-uml-id duplicate-clang-id})))
    (when duplicate-id
      (throw (ex-info "clang-uml classes map to the same viewer ID"
                      {:id duplicate-id})))
    (let [ids (into {} (map (fn [c] [(:clang-uml-id c) (:id c)]) converted))
          unsupported-diags
          (mapv #(diagnostic :unsupported-element (qualified-name %)
                             (str "clang-uml element type '" (:type %) "' is not supported"))
                unsupported-elements)
          class-diags
          (mapcat (fn [[element converted-class]]
                    (cond-> []
                      (nil? (:source-ident converted-class))
                      (conj (diagnostic :missing-source-location
                                        (:qualified-name converted-class)
                                        "clang-uml did not provide a usable class source_location"))
                      (some #(nil? (:source-ident %)) (:ops converted-class))
                      (conj (diagnostic :missing-method-location
                                        (:qualified-name converted-class)
                                        "one or more methods have no usable source_location"))
                      (some #(nil? (:source-ident %)) (:fields converted-class))
                      (conj (diagnostic :missing-member-location
                                        (:qualified-name converted-class)
                                        "one or more members have no usable source_location"))))
                  (map vector classes converted))
          [edges relation-diags]
          (reduce (fn [[edges diagnostics] relationship]
                    (let [kind (relationship-kind (:type relationship))
                          from (get ids (str (:source relationship)))
                          to (get ids (str (:destination relationship)))
                          entity (str (:type relationship) " "
                                      (:source relationship) " -> "
                                      (:destination relationship))]
                      (cond
                        (nil? kind)
                        [edges (conj diagnostics
                                     (diagnostic :unsupported-relationship entity
                                                 (str "clang-uml relationship type '"
                                                      (:type relationship)
                                                      "' is not supported")))]
                        (or (nil? from) (nil? to))
                        [edges (conj diagnostics
                                     (diagnostic :unresolved-relationship entity
                                                 "relationship endpoint is absent from the emitted class elements"))]
                        :else
                        [(conj edges
                               (cond-> {:from from :to to :kind kind}
                                 (seq (:label relationship))
                                 (assoc :label (:label relationship))
                                 (seq (:access relationship))
                                 (assoc :access (:access relationship))))
                         diagnostics])))
                  [[] []]
                  (or (:relationships diagram) []))
          base-diags
          (for [element classes
                base (:bases element)
                :let [base-id (str (:id base))
                      child-id (str (:id element))]
                :when (and (contains? by-clang-id base-id)
                           (not (some #(and (= child-id (str (:source %)))
                                            (= base-id (str (:destination %)))
                                            (= "extension" (:type %)))
                                      (:relationships diagram))))]
            (diagnostic :missing-inheritance-relationship
                        (str (qualified-name element) " -> "
                             (qualified-name (get by-clang-id base-id)))
                        "clang-uml lists a base but emitted no extension relationship"))
          diagnostics (vec (concat unsupported-diags class-diags relation-diags base-diags))]
      {:title (or (:name diagram) "C++ class diagram")
       :language :cpp
       :hierarchical true
       :preserve-parallel-edges true
       :source-root root
       :metrics-status :unavailable
       :classes converted
       :edges edges
       :order (->> converted (map :id) (map #(keyword (first (str/split (name %) #"\.")))) distinct vec)
       :diagnostics diagnostics})))
