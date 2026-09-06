(ns kami.app-suji.core-test
  "The views are pure functions of state, so they can be asserted on directly.

  What is worth asserting here is not that hiccup renders — it is that the page
  does not make a claim the numbers do not support. The spine table is the case
  that has already been wrong once: `suji` reported muscle and ligament axial
  force added together in a field named `:muscle-n`, and a reader taking the name
  literally would have read a spine compressed by muscle where it is compressed
  by ligament — a different statement about the posture, and about what would
  change it."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kami.app-suji.core :as core]
            [suji.methods.posture :as posture]
            [suji.methods.spine :as spine]))

(defn- flat-text
  "Every string anywhere in a hiccup tree, joined."
  [x]
  (cond (string? x) x
        (sequential? x) (str/join " " (map flat-text x))
        :else ""))

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

;; --- the lower limb reaches the page ----------------------------------------

(deftest every-preset-the-panel-offers-can-actually-be-applied
  ;; The panel rendered its buttons from one list and the click handler resolved
  ;; them against another, so a preset added to the panel would have rendered as a
  ;; button that did nothing when pressed — visibly live, silently inert. Both now
  ;; read `core/presets`; this asserts they agree by resolving every button.
  (let [named (map :name core/presets)]
    (is (seq named) "no presets, so this asserts nothing")
    (doseq [n named]
      (is (some? (core/preset-posture n))
          (str "the panel offers `" n "` and nothing resolves it")))
    ;; and the buttons the panel actually renders carry those same names
    (let [rendered (->> (core/control-panel core/initial-state)
                        (nodes :button)
                        (keep #(get-in % [1 :data-preset]))
                        set)]
      (is (= (set named) rendered)
          (str "buttons rendered " rendered " for presets " (set named))))))

(deftest both-support-regimes-are-reachable-from-the-panel
  ;; The support mode is the largest single fact in the lower-limb model and it is
  ;; not a slider, so the only way to reach standing is a preset. If every preset
  ;; were seated, the whole standing half of the model would be unreachable from
  ;; the browser while looking fully present in the physics.
  (let [modes (set (map #(posture/support-mode (:posture %)) core/presets))]
    (is (contains? modes :seated) "no seated preset")
    (is (contains? modes :standing) "no standing preset")))

(deftest the-panel-says-which-regime-the-numbers-came-from
  ;; Same joint angles, two different answers. Without this the reader cannot tell
  ;; a 10 N ankle from a 333 N one except by knowing the model.
  (let [seated (core/control-panel core/initial-state)
        standing (core/control-panel
                  (assoc core/initial-state :posture (core/preset-posture "standing-neutral")))]
    (is (str/includes? (flat-text seated) "座位"))
    (is (str/includes? (flat-text standing) "立位"))
    (is (not= (flat-text seated) (flat-text standing))
        "the panel reads identically in both regimes")))

(deftest the-lower-limb-joints-have-controls
  (let [paths (set (map :path core/controls))]
    (doseq [p [[:posture :hip-flexion-deg] [:posture :knee-flexion-deg]
               [:posture :ankle-dorsiflexion-deg]]]
      (is (contains? paths p) (str "no control for " p)))))

(deftest the-support-regime-reaches-the-page-as-a-figure
  ;; The physics claim, asserted through the app so a pin bump that broke it fails
  ;; here rather than only in the library.
  ;;
  ;; I first wrote this against the ankle JOINT MOMENT and it read 0.0 in both
  ;; regimes — correctly. A body stacked over its ankles asks nothing of its
  ;; calves, and `lower-limb-loads` puts the centre of pressure under the line of
  ;; gravity rather than taking it as a parameter, so a neutral stand has almost
  ;; no ankle moment. The regime shows in the GROUND REACTION, which is the one
  ;; force the whole difference consists of.
  (let [sup (fn [preset]
              (:support (:loads (core/solved
                                 (assoc core/initial-state
                                        :posture (core/preset-posture preset))))))
        st (sup "standing-neutral")
        se (sup "laptop-on-lap")]
    (is (= :standing (:mode st)))
    (is (= :seated (:mode se)))
    (is (zero? (:ground-reaction-per-foot-n se))
        "a seated body pushes on no floor")
    ;; half the body weight per foot, which is the whole of the difference
    (is (< 0.45 (/ (:ground-reaction-per-foot-n st) (:body-weight-n st)) 0.55)
        (str "ground reaction " (:ground-reaction-per-foot-n st)
             " N against a body weight of " (:body-weight-n st) " N"))
    ;; And it is on the page as a FIGURE, not merely in the map.
    ;;
    ;; Asserted on the readout item's structure, not by searching the page text —
    ;; the paragraph above the figures explains what the ground reaction is and
    ;; uses the same word, so a text search stays green after the figure is
    ;; deleted. (Measured: it did. This is the third time this exact shape has
    ;; been found in this repo in two days, twice in checks I wrote myself.)
    (let [items (->> (core/simulate-view
                      (assoc core/initial-state
                             :posture (core/preset-posture "standing-neutral")))
                     (nodes :div)
                     (filter #(= "suji-readout-item" (:class (second %)))))
          labelled (fn [label]
                     (first (filter #(str/includes? (flat-text %) label) items)))
          grf (labelled "床反力")]
      (is (some? grf)
          (str "no readout figure for the ground reaction; labels present: "
               (pr-str (mapv #(last (str/split (str/trim (flat-text %)) #"\s+")) items))))
      (is (re-find #"\d" (flat-text grf))
          (str "the ground-reaction figure carries no number: " (pr-str (flat-text grf)))))))

(deftest the-spine-table-names-the-muscles-crossing-each-level
  ;; The 筋ぶん column is a sum, and a sum cannot be checked. When `spine/crosses?`
  ;; was a half-space test on height, the whole C3/C4 row was carried by the two
  ;; wrist extensors — a force that transmits to the forearm, credited to a neck —
  ;; and no reader of this page could have seen it, because the page printed only
  ;; the total. This column is what makes the next error of that kind visible.
  (let [view (core/spine-view (flexed 20))
        hs (headers view)
        rows (map #(mapv text-of (nodes :td %)) (nodes :tr view))
        body (core/body-of (flexed 20))
        model (spine/profile body (:posture (flexed 20))
                             (:tensions (core/solved (flexed 20))))]
    (is (some #{"跨いでいる筋"} hs) (str "no crossing column; headers were " hs))
    (let [i (first (keep-indexed #(when (= "跨いでいる筋" %2) %1) hs))
          cells (keep #(when (= (count hs) (count %)) (nth % i)) rows)]
      (is (= (count model) (count cells))
          (str "the model has " (count model) " levels and the table has "
               (count cells) " crossing cells"))
      ;; every muscle the model says crosses a level is named in that level's cell
      (doseq [[r cell] (map vector model cells)]
        (doseq [m (map first (:muscle-crossing r))]
          (is (str/includes? cell (str/replace m "_" " "))
              (str (:name r) ": " m " crosses it and is not in the cell: " cell))))
      ;; ⚠ The "no muscle crosses this level" case is NOT asserted here. At 20° of
      ;; trunk flexion every level has something crossing it, so a `when (empty?
      ;; …)` guard would have run its body zero times and passed while measuring
      ;; nothing — which is what the first version of this test did, and the break
      ;; that rendered an empty set as a blank cell did not fail it. Measured over
      ;; 360 level-samples across twelve postures, 13 are empty, and all of them
      ;; are at trunk 0°. It gets its own test below, at a posture where the case
      ;; actually occurs.
      )))

(deftest a-level-with-no-muscle-crossing-it-says-so-rather-than-rendering-blank
  ;; A blank cell reads as a rendering failure. `L1/L2` genuinely has nothing
  ;; crossing it upright — the muscles that span the lumbar column insert below
  ;; it — and the page has to say that rather than leave a hole.
  ;;
  ;; The posture is chosen because the case OCCURS there, and the test asserts it
  ;; occurs before asserting what it renders as: 13 of 360 level-samples across
  ;; twelve postures are empty and every one is at trunk 0°.
  (let [st (-> core/initial-state
               (assoc-in [:posture :trunk-flexion-deg] 0.0)
               (assoc-in [:posture :head-flexion-deg] 0.0))
        model (spine/profile (core/body-of st) (:posture st) (:tensions (core/solved st)))
        empty-levels (filter #(empty? (:muscle-crossing %)) model)
        view (core/spine-view st)
        hs (headers view)
        i (first (keep-indexed #(when (= "跨いでいる筋" %2) %1) hs))
        cells (->> (nodes :tr view)
                   (map #(mapv text-of (nodes :td %)))
                   (keep #(when (= (count hs) (count %)) (nth % i))))]
    (is (seq empty-levels)
        "no level is empty at this posture, so this test asserts nothing")
    (doseq [[r cell] (map vector model cells)]
      (when (empty? (:muscle-crossing r))
        (is (str/includes? cell "なし")
            (str (:name r) ": no muscle crosses it and the cell is " (pr-str cell)))))))

(deftest the-crossing-column-is-not-the-same-for-every-level
  ;; The control. Without it a cell that printed the whole muscle set, or one
  ;; constant string, would satisfy every assertion above.
  (let [view (core/spine-view (flexed 20))
        hs (headers view)
        i (first (keep-indexed #(when (= "跨いでいる筋" %2) %1) hs))
        cells (->> (nodes :tr view)
                   (map #(mapv text-of (nodes :td %)))
                   (keep #(when (= (count hs) (count %)) (nth % i))))]
    (is (< 1 (count (distinct cells)))
        (str "every level lists the same muscles: " (pr-str (distinct cells))))
    ;; a lumbar level and a cervical level must not agree — they share no muscle
    (let [model (spine/profile (core/body-of (flexed 20)) (:posture (flexed 20))
                               (:tensions (core/solved (flexed 20))))
          named (fn [region]
                  (set (mapcat #(map first (:muscle-crossing %))
                               (filter #(= region (:region %)) model))))]
      (is (empty? (clojure.set/intersection (named :lumbar) (named :cervical)))
          (str "a muscle crosses both a lumbar and a cervical level: "
               (pr-str (clojure.set/intersection (named :lumbar) (named :cervical))))))))
