(ns kami.app-suji.core-test
  "The views are pure functions of state, so they can be asserted on directly.

  What is worth asserting here is not that hiccup renders — it is that the page
  does not make a claim the numbers do not support. The spine table is the case
  that has already been wrong once: `suji` reported muscle and ligament axial
  force added together in a field named `:muscle-n`, and a reader taking the name
  literally would have read a spine compressed by muscle where it is compressed
  by ligament — a different statement about the posture, and about what would
  change it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kami.app-suji.core :as core]
            [suji.methods.spine :as spine]))

(defn- nodes
  "Every hiccup element in the tree whose tag is `tag`."
  [tag x]
  (cond (and (vector? x) (= tag (first x)))
        (cons x (mapcat #(nodes tag %) x))
        (sequential? x) (mapcat #(nodes tag %) x)
        :else nil))

(defn- text-of
  "The strings directly inside one hiccup element."
  [el]
  (str/join (filter string? el)))

(defn- headers
  "The column headings of the first table in a view.

  Read out of the rendered `[:th …]` elements rather than out of the prose,
  because prose that TALKS about a column reads the same to `str/includes?` as a
  column that exists. That is not hypothetical: the first version of this test
  went on passing after the column was deleted, because the paragraph above the
  table names both terms."
  [view]
  (mapv text-of (nodes :th view)))

(defn- flexed [deg]
  (assoc-in core/initial-state [:posture :trunk-flexion-deg] (double deg)))

(deftest a-direction-cosine-is-never-printed-as-millimetres
  ;; `recruit` returns a moment arm in metres for a moment equilibrium and a
  ;; dimensionless direction cosine for a suspended force. The unit guard in
  ;; `coeff-label` looked the task up in a GROUP-keyed map by INSTANCE name, so it
  ;; returned nil for every paired muscle and never fired: the default view shipped
  ;; six rows reading "343.3 mm" / "480.9 mm" / "176.9 mm" — a 34 cm moment arm at
  ;; the shoulder.
  ;;
  ;; Asserted through `coeff-label` on real solved tensions rather than on a
  ;; hand-built map, because the defect was the shape of the real data (instance
  ;; names) and a constructed entry would have been given the shape the code
  ;; expected.
  (let [ts (:tensions (core/solved core/initial-state))
        susp (filter #(= :scapular-suspension (:task %)) ts)
        moments (filter #(and (:task %) (not= :scapular-suspension (:task %))
                              (number? (:coeff %))) ts)]
    (is (seq susp) "the default posture must actually contain suspension tasks")
    (is (every? #(str/includes? (core/coeff-label %) "(cos)") susp)
        (str "suspension coefficients are cosines, got "
             (pr-str (mapv (juxt :name core/coeff-label) susp))))
    (is (not-any? #(str/includes? (core/coeff-label %) "mm") susp)
        (str "no suspension row may carry a length unit, got "
             (pr-str (mapv (juxt :name core/coeff-label) susp))))
    ;; The control: without it this test would also pass if EVERY row lost its "mm".
    (is (seq moments) "the default posture must also contain moment tasks")
    (is (every? #(str/includes? (core/coeff-label %) "mm") moments)
        (str "moment-arm coefficients are still millimetres, got "
             (pr-str (mapv (juxt :name core/coeff-label) moments))))))

(deftest the-spine-table-names-muscle-and-ligament-separately
  (let [hs (headers (core/spine-view (flexed 60)))]
    (is (some #{"筋ぶん"} hs) (str "headers were " hs))
    (is (some #{"靭帯ぶん"} hs)
        (str "the table must carry the ligament term as its own column, headers were " hs))))

(deftest every-spine-row-fills-every-column
  ;; A header added without its cell (or the reverse) silently shifts every
  ;; figure one column to the left, which is worse than omitting it.
  (let [view (core/spine-view (flexed 60))
        n    (count (headers view))
        rows (map #(count (nodes :td %)) (nodes :tr view))]
    (is (pos? n))
    (is (every? #(or (zero? %) (= n %)) rows)
        (str "expected " n " cells per row, got " (vec rows)))))

(deftest deep-flexion-puts-the-compression-on-the-ligaments-not-the-muscles
  ;; The reason the split has to reach the page. At 60° of trunk flexion the
  ;; erector spinae fall silent (flexion-relaxation) and the posterior
  ;; ligamentous system takes the moment — so the lumbar levels are compressed
  ;; by tissue that no amount of "relax your back" would unload.
  (let [state (flexed 60)
        rows  (spine/profile (core/body-of state)
                             (:posture state)
                             (:tensions (core/solved state)))
        l5s1  (first (filter #(= "L5/S1" (:name %)) rows))]
    (is (some? l5s1))
    (is (number? (:ligament-n l5s1)))
    (is (> (:ligament-n l5s1) (:muscle-n l5s1))
        (str "at 60° the ligament term should exceed the muscle term, got "
             (select-keys l5s1 [:muscle-n :ligament-n])))
    ;; And the two really are separate quantities, not one value copied twice.
    (is (not= (:ligament-n l5s1) (:muscle-n l5s1)))))

(deftest upright-puts-it-back-on-the-muscles
  ;; The control for the test above: the same assertion must NOT hold upright,
  ;; or it is measuring the field name rather than the posture.
  (let [state (flexed 0)
        rows  (spine/profile (core/body-of state)
                             (:posture state)
                             (:tensions (core/solved state)))
        l5s1  (first (filter #(= "L5/S1" (:name %)) rows))]
    (is (zero? (:ligament-n l5s1))
        (str "upright, the posterior ligaments are slack, got "
             (select-keys l5s1 [:muscle-n :ligament-n])))))
