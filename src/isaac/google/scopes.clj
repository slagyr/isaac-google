(ns isaac.google.scopes
  (:require
    [isaac.module.berths :as berths]
    [isaac.module.discovery :as discovery]))

(def OPENID "openid")

(defn- contribution-scopes [contribution]
  (cond
    (string? contribution) [contribution]
    (sequential? contribution) (vec contribution)
    (and (map? contribution) (:scope contribution)) [(:scope contribution)]
    (and (map? contribution) (:scopes contribution)) (vec (:scopes contribution))
    :else []))

(defn union
  "Union of :isaac.google/scopes contributions across `module-index`,
   always including openid. Order: openid first, then remaining in
   contribution order, de-duplicated."
  ([] (union (discovery/builtin-index)))
  ([module-index]
   (let [raw (->> (berths/contributions-to-berth module-index :isaac.google/scopes)
                  (mapcat (fn [[_ contribution]] (contribution-scopes contribution)))
                  (remove nil?)
                  vec)
         rest (into [] (comp (remove #{OPENID}) (distinct)) raw)]
     (into [OPENID] rest))))
