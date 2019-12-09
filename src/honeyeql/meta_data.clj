(ns honeyeql.meta-data
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [inflections.core :as inf]))

(defn- meta-data-result [db-spec result-set]
  (rs/datafiable-result-set result-set db-spec {:builder-fn rs/as-unqualified-lower-maps}))

(defn- coarce-boolean [bool-str]
  (case bool-str
    "YES" true
    "NO" false))

(defn- entity-ident
  ([db-config {:keys [table_schem table_name]}]
   (entity-ident db-config table_schem table_name))
  ([db-config table_schem table_name]
   (if (= (get-in db-config [:schema :default]) table_schem)
     (keyword (inf/singular (inf/hyphenate table_name)))
     (keyword (inf/hyphenate table_schem) (inf/hyphenate table_name)))))

(defn- attribute-ident
  ([db-config {:keys [table_schem table_name column_name]}]
   (attribute-ident db-config table_schem table_name column_name))
  ([db-config table_schem table_name column_name]
   (if (= (get-in db-config [:schema :default]) table_schem)
     (keyword (inf/singular (inf/hyphenate table_name)) (inf/hyphenate column_name))
     (keyword (str (inf/hyphenate table_schem) "." (inf/singular (inf/hyphenate table_name)))
              (inf/hyphenate column_name)))))

(defn- column-ident [db-config {:keys [table_schem table_name column_name]}]
  (if (= (get-in db-config [:schema :default]) table_schem)
    (keyword (str table_name "." column_name))
    (keyword (str table_schem "." table_name "." column_name))))

(defn- to-entity-meta-data [db-config {:keys [remarks table_type table_schem table_name]
                                       :as   table-meta-data}]
  (let [ident (entity-ident db-config table-meta-data)]
    [ident
     {:entity/doc                   remarks
      :entity/ident                 ident
      :entity.relation/type         (case table_type
                                      "TABLE" :table
                                      "VIEW" :view)
      :entity.relation/schema       table_schem
      :entity.relation/name         table_name
      :entity/opt-attrs             #{}
      :entity/req-attrs             #{}
      :entity.relation/primary-key  {}
      :entity.relation/foreign-keys #{}
      :entity.relation/ident        (keyword (str table_schem "." table_name))}]))

(defn- entities-meta-data [db-spec jdbc-meta-data db-config]
  (->> (into-array String ["TABLE" "VIEW"])
       (.getTables jdbc-meta-data nil "%" nil)
       (meta-data-result db-spec)
       (map #(to-entity-meta-data db-config %))
       (into {})))

(defn- filter-columns [db-config columns]
  (remove #(contains? (get-in db-config [:schema :ignore]) (:table_schem %)) columns))

(defmulti derive-attr-type
  (fn [db-config _]
    (:db-product-name db-config)))

(defn- add-attribute-meta-data [db-config heql-meta-data
                                {:keys [table_schem table_name column_name data_type column_size
                                        remarks is_nullable is_autoincrement type_name ordinal_position]
                                 :as   column-meta-data}]
  (let [attr-ident            (attribute-ident db-config column-meta-data)
        entity-ident          (entity-ident db-config column-meta-data)
        is-nullable           (coarce-boolean is_nullable)
        entity-attr-qualifier (if is-nullable :entity/opt-attrs :entity/req-attrs)]
    (update-in
     (assoc-in heql-meta-data [:attributes attr-ident]
               {:attr/ident                     attr-ident
                :attr/doc                       remarks
                :attr/type                      (derive-attr-type db-config column-meta-data)
                :attr/nullable                  is-nullable
                :attr.column/name               column_name
                :attr.column/schema             table_schem
                :attr.column/relation           table_name
                :attr.column/size               column_size
                :attr.column/auto-incrementable (coarce-boolean is_autoincrement)
                :attr.column/jdbc-type          data_type
                :attr.column/db-type            type_name
                :attr.column/ident              (column-ident db-config column-meta-data)
                :attr.column/ordinal-position   ordinal_position
                :attr.entity/ident              entity-ident})
     [:entities entity-ident entity-attr-qualifier]
     conj attr-ident)))

(defn- add-attributes-meta-data [db-spec jdbc-meta-data db-config heql-meta-data]
  (->> (.getColumns jdbc-meta-data nil "%" "%" nil)
       (meta-data-result db-spec)
       (filter-columns db-config)
       (reduce (partial add-attribute-meta-data db-config) heql-meta-data)))

(defn- add-primary-keys-meta-data [db-spec jdbc-meta-data db-config heql-meta-data]
  (let [schemas-to-ignore (get-in db-config [:schema :ignore])]
    (->> (.getPrimaryKeys jdbc-meta-data nil "" nil)
         (meta-data-result db-spec)
         (remove #(contains? schemas-to-ignore (:table_schem %)))
         (group-by (fn [{:keys [table_schem table_name]}]
                     [table_schem table_name]))
         (reduce (fn [pks [[table_schem table_name] v]]
                   (assoc pks
                          (entity-ident db-config {:table_schem table_schem
                                                   :table_name  table_name})
                          {:entity.relation/primary-key {:entity.relation.primary-key/name  (:pk_name (first v))
                                                         :entity.relation.primary-key/attrs (set (map #(attribute-ident db-config %) v))}})) {})
         (merge-with merge (:entities heql-meta-data))
         (assoc heql-meta-data :entities))))

(defmulti get-db-config identity)

(defn- foreign-key-column->attr-name [{:keys [foreign-key-suffix]} fkcolumn_name]
  (if foreign-key-suffix
    (clojure.string/replace fkcolumn_name (re-pattern (str foreign-key-suffix "$")) "")
    fkcolumn_name))

(defn- add-fk-rel-meta-data [db-config hql-meta-data
                             {:keys [fktable_schem fktable_name fkcolumn_name fk_name
                                     pktable_schem pktable_name pkcolumn_name]}]
  (let [one-to-one-attr-name   (foreign-key-column->attr-name db-config fkcolumn_name)
        one-to-one-attr-ident  (attribute-ident db-config fktable_schem fktable_name one-to-one-attr-name)
        left-attr-ident        (attribute-ident db-config fktable_schem fktable_name fkcolumn_name)
        left-entity-ident      (entity-ident db-config fktable_schem fktable_name)
        right-attr-ident       (attribute-ident db-config pktable_schem pktable_name pkcolumn_name)
        right-entity-ident     (entity-ident db-config pktable_schem pktable_name)
        is-nullable            (get-in hql-meta-data [:attributes left-attr-ident :attr/nullable])
        one-to-many-attr-ident (keyword (name right-entity-ident) (inf/plural (name left-entity-ident)))]
    {:attributes {one-to-one-attr-ident  {:attr/ident            one-to-one-attr-ident
                                          :attr/type             :attr.type/ref
                                          :attr/nullable         is-nullable
                                          :attr.ref/cardinality  :attr.ref.cardinality/one
                                          :attr.ref/type         right-entity-ident
                                          :attr.column.ref/left  left-attr-ident
                                          :attr.column.ref/right right-attr-ident}
                  one-to-many-attr-ident {:attr/ident            one-to-many-attr-ident
                                          :attr/type             :attr.type/ref
                                          :attr/nullable         false
                                          :attr.ref/cardinality  :attr.ref.cardinality/many
                                          :attr.ref/type         left-entity-ident
                                          :attr.column.ref/left  right-attr-ident
                                          :attr.column.ref/right left-attr-ident}}
     :entities   {left-entity-ident  (update
                                      (update (get-in hql-meta-data [:entities left-entity-ident])
                                              (if is-nullable :entity/opt-attrs :entity/req-attrs)
                                              conj one-to-one-attr-ident)
                                      :entity.relation/foreign-keys
                                      conj {:entity.relation.foreign-key/name      fk_name
                                            :entity.relation.foreign-key/self-attr left-attr-ident
                                            :entity.relation.foreign-key/ref-attr  right-attr-ident})
                  right-entity-ident (update (get-in hql-meta-data [:entities right-entity-ident])
                                             :entity/req-attrs
                                             conj one-to-many-attr-ident)}}))

(defn- add-relationships-meta-data [db-spec jdbc-meta-data db-config hql-meta-data]
  (->> (.getImportedKeys jdbc-meta-data nil "" nil)
       (meta-data-result db-spec)
       (reduce #(merge-with merge %1 (add-fk-rel-meta-data db-config %1 %2)) hql-meta-data)))

(defn- add-many-to-many-meta-data [hql-meta-data fks-meta-data]
  (let [[{:entity.relation.foreign-key/keys [ref-attr self-attr]} & xs] fks-meta-data]
    (if (seq xs)
      ; (do
      ;   (prn ref-attr self-attr "----")
      ;   (run! #(prn (:entity.relation.foreign-key/ref-attr %) (:entity.relation.foreign-key/self-attr %)) xs)
      ;   (add-many-to-many-meta-data hql-meta-data xs))
      (add-many-to-many-meta-data
       (reduce (fn [h-md fk-md]
                 (let [r-ref-attr              (:entity.relation.foreign-key/ref-attr fk-md)
                       r-self-attr             (:entity.relation.foreign-key/self-attr fk-md)
                       left-entity-ident            (get-in h-md [:attributes ref-attr :attr.entity/ident])
                       right-entity-ident           (get-in h-md [:attributes r-ref-attr :attr.entity/ident])
                       many-to-many-attr-ident (keyword (name left-entity-ident) (inf/plural (name right-entity-ident))) 
                       many-to-many-rev-attr-ident (keyword (name right-entity-ident) (inf/plural (name left-entity-ident)))]
                   (when-not (or (get-in h-md [:attributes many-to-many-attr-ident])
                                 (get-in h-md [:attributes many-to-many-rev-attr-ident]))
                      (prn many-to-many-attr-ident))
                   h-md
                   #_(if (get-in h-md [:attributes many-to-many-attr-ident])
                     h-md
                     (update h-md :attributes
                             {many-to-many-attr-ident {:attr/ident                        many-to-many-attr-ident
                                                       :attr/type                         :attr.type/ref
                                                       :attr/nullable                     false
                                                       :attr.ref/cardinality              :attr.ref.cardinality/many
                                                       :attr.ref/type                     left-entity-ident
                                                       :attr.column.ref/left              ref-attr
                                                       :attr.column.ref.associative/left  self-attr
                                                       :attr.column.ref.associative/right r-self-attr
                                                       :attr.column.ref/right             r-ref-attr}})))) hql-meta-data xs)
       xs)
      hql-meta-data)))

(defn- add-many-to-many-rels-meta-data [hql-meta-data]
  (reduce (fn [hql-md [_ e-md]]
            (if-let [fks-md (seq (:entity.relation/foreign-keys e-md))]
              (add-many-to-many-meta-data hql-md fks-md)
              hql-md))
          hql-meta-data
          (:entities hql-meta-data)))

(defn fetch [db-spec]
  (with-open [conn (jdbc/get-connection db-spec)]
    (let [jdbc-meta-data  (.getMetaData conn)
          db-product-name (.getDatabaseProductName jdbc-meta-data)
          db-config       (assoc (get-db-config db-product-name) :db-product-name db-product-name)]
      (->> (entities-meta-data db-spec jdbc-meta-data db-config)
           (hash-map :entities)
           (add-attributes-meta-data db-spec jdbc-meta-data db-config)
           (add-primary-keys-meta-data db-spec jdbc-meta-data db-config)
           (add-relationships-meta-data db-spec jdbc-meta-data db-config)
           add-many-to-many-rels-meta-data))))