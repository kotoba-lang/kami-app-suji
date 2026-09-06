(ns kami.app-suji.scene
  "kami-app-suji — posture + solved loads → a render scene, as pure data.

  This namespace is `.cljc` and holds NO rendering: no canvas, no GPU object, no
  `js/`. It turns what `suji` computed into the draw list `kami.webgpu.mesh` /
  `kami.webgl` consume, so the picture is testable without a browser and the thing
  under test is the mapping rather than the driver.

  AUTHORITY. The chain's placement comes from `suji.methods.pose`; the tessellation
  comes from `kami.webgpu.geometry`. This app tessellates nothing and places nothing
  — if a bone is in the wrong place here it is in the wrong place in the physics too,
  which is the point of them sharing one source.

  COLOUR IS A CLAIM. Each bone is tinted by the load carried at the joint it hangs
  from, expressed as the highest %MVC among the muscles that cross that joint. That
  is a mechanical quantity (force ÷ maximum voluntary force), and the bands below are
  the same ones `strain/stiffness-band` already uses for text — so the picture and
  the table cannot say different things. NON-DIAGNOSTIC (G1): a colour is not a
  finding, and there is no colour in this file that means anything clinical."
  (:require [suji.methods.attachment :as attachment]
            [suji.methods.load :as load]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.pose :as pose]
            [suji.methods.spine :as spine]))

;; --- which muscles cross which joint -----------------------------------------
;; A segment's load is the load at the joint it hangs from. These are the muscle
;; groups `suji.methods.muscle` solves, assigned to the joint they act about.
(def joint-muscles
  "Which muscle GROUPS act about the joint each segment hangs from.

  Groups, not instances: the model is bilateral, so `upper_trapezius` names two
  muscles and a segment on the left is coloured by the left one. Matching on the
  instance name would have needed this table written twice; matching on the group
  and the segment's own side needs it written once."
  {"head_neck"      #{"cervical_extensors" "scalenes"}
   "thorax_abdomen" #{"erector_spinae" "quadratus_lumborum" "obliques"}
   "upper_arm"      #{"anterior_deltoid" "middle_deltoid" "latissimus_dorsi"
                      "upper_trapezius" "middle_trapezius" "levator_scapulae"}
   ;; the forearm and hand hang off the ELBOW, not off the shoulder — the shoulder
   ;; carries them too, but the joint they are attached to is the one whose load
   ;; their colour should report
   "forearm"        #{"biceps_brachii" "brachialis" "triceps_brachii"}
   "hand"           #{"biceps_brachii" "brachialis" "triceps_brachii"}
   "pelvis"         #{}})

;; --- the load ramp -----------------------------------------------------------
;; Linear RGB for the GPU, not CSS: these never reach a stylesheet, so the
;; design-system token contract does not apply to them (it governs app CSS). The
;; breakpoints are `strain/stiffness-band`'s, restated as %MVC so one scale drives
;; both the 3-D view and the table.
(def load-bands
  [{:band "low"       :max-mvc-pct 5.0   :rgb [0.36 0.72 0.47]}
   {:band "moderate"  :max-mvc-pct 15.0  :rgb [0.85 0.76 0.32]}
   {:band "high"      :max-mvc-pct 30.0  :rgb [0.90 0.55 0.24]}
   {:band "very-high" :max-mvc-pct math/inf :rgb [0.82 0.29 0.29]}])

(def unloaded-rgb
  "The base segment — a segment no solved muscle acts about (the pelvis)."
  [0.55 0.57 0.62])

(def antagonist-rgb
  "A muscle that could act but is not being asked to, because its opposite number
  is carrying this instant's load. Dimmer than every load colour: it is neither
  loaded nor unanswerable, and drawing it like either would be a claim."
  [0.30 0.33 0.40])

(def refused-rgb
  "A segment whose load the model declined to compute. Deliberately unlike both
  ends of the load ramp: it must not read as 'fine' or as 'bad', because it is
  neither — it is 'not answered'."
  [0.42 0.35 0.62])

(defn band-for
  "The load band a %MVC falls in."
  [mvc-pct]
  (first (filter #(< mvc-pct (:max-mvc-pct %)) load-bands)))

(defn- lerp [a b t] (+ a (* (- b a) t)))

(defn ramp-rgb
  "Colour for a %MVC. Interpolated WITHIN the band toward the next one, so a value
  moving through a band is visible before it crosses into the next — a hard step
  hides most of the range."
  [mvc-pct]
  (let [idx (count (take-while #(>= mvc-pct (:max-mvc-pct %)) load-bands))
        idx (min idx (dec (count load-bands)))
        this (nth load-bands idx)
        prev-max (if (zero? idx) 0.0 (:max-mvc-pct (nth load-bands (dec idx))))
        nxt (nth load-bands (min (inc idx) (dec (count load-bands))))
        span (- (:max-mvc-pct this) prev-max)
        t (if (math/finite? span)
            (math/clamp (/ (- mvc-pct prev-max) (max 1e-9 span)) 0.0 1.0)
            0.0)]
    (mapv #(lerp %1 %2 (* 0.65 t)) (:rgb this) (:rgb nxt))))

(defn segment-state
  "What this segment's colour is allowed to claim.

  Three outcomes, and they are NOT interchangeable:
    {:kind :loaded  :mvc-pct n}  — a muscle acts here and the model solved it
    {:kind :none}                — no solved muscle acts about this joint (the pelvis)
    {:kind :refused :names [..]} — a muscle acts here and the model DECLINED to
                                   compute its force at this posture

  The third is the one that matters. `suji` refuses when a muscle's line of action
  passes too close to the joint it acts about — a straight-line model has no
  wrapping surface, so the force there diverges. Colouring a refused segment green
  would say the posture is easy; colouring it red would say it is hard; both are
  claims the model explicitly declined to make."
  [tensions base side]
  (let [groups (get joint-muscles base #{})
        mine (filter #(and (contains? groups (:group %))
                           (or (= :midline (:side %)) (= side (:side %))))
                     tensions)
        ;; an antagonist is not an unanswered load; it must not turn a segment purple
        refused (remove :antagonist? (filter :refused mine))
        vals (keep :mvc-pct mine)]
    (cond
      (empty? mine) {:kind :none}
      (seq refused) {:kind :refused :names (mapv :name refused)}
      (seq vals) {:kind :loaded :mvc-pct (apply max vals)}
      :else {:kind :none})))

;; --- geometry placement ------------------------------------------------------
;; `kami.webgpu.geometry/cylinder` is built along +Y and centred, so a bone is that
;; unit cylinder scaled to its length, rotated about Z onto its direction, and
;; translated to its midpoint. The pose already carries the rotation as `:euler-z`,
;; so this is placement, not trigonometry.

(def bone-radius-m
  "Drawn thickness per segment (metres). Visual only — the physics is a line-mass
  model and carries no cross-section, so this is honestly decoration and is stated
  here rather than hidden in a shader."
  {"pelvis" 0.055 "thorax_abdomen" 0.062 "head_neck" 0.048
   "upper_arm" 0.032 "forearm" 0.026 "hand" 0.020})

(defn bone-draws
  "One draw per placed segment."
  [pose-data tensions]
  (mapv (fn [{:keys [name base side proximal distal length-m euler-z] :as placed}]
          (let [mid (math/vmid proximal distal)
                {:keys [kind mvc-pct] :as st} (segment-state tensions base side)]
            {:label name
             :geo :cylinder
             :radius-m (get bone-radius-m base 0.03)
             :state kind
             :refused-names (:names st)
             :mvc-pct mvc-pct
             :band (when mvc-pct (:band (band-for mvc-pct)))
             :color (case kind
                      :loaded (ramp-rgb mvc-pct)
                      :refused refused-rgb
                      unloaded-rgb)
             :transform {:translation mid
                         :rotation [0.0 0.0 euler-z]
                         :scale [1.0 length-m 1.0]}
             :com (:com placed)}))
        (:segments pose-data)))

(defn euler-for-direction
  "XYZ Euler angles that carry a unit cylinder's +Y axis onto `d`.

  `kami.webgpu.mesh/model-matrix` composes Rz·Ry·Rx, so with ry = 0 the image of
  +Y is [−sin(rz)cos(rx), cos(rz)cos(rx), sin(rx)] — which inverts in closed form.
  Bones only ever tilt in the sagittal plane and carry `:euler-z` for that; muscles
  do not, so they need the general case."
  [[dx dy dz]]
  (let [dz (math/clamp dz -1.0 1.0)
        rx (Math/asin dz)
        rz (Math/atan2 (- dx) dy)]
    [rx 0.0 rz]))

(def muscle-radius-m
  "Drawn thickness of a line of action. Visual only — the model is a line and has
  no cross-section — so it is stated here rather than implied by a shader."
  0.008)

(defn muscle-draws
  "One thin rod per muscle instance, along its actual line of action, tinted by the
  same %MVC ramp the bones use.

  This is the only anatomy in this app that is neither a schematic cylinder nor a
  landmark sphere: the endpoints are where `suji` says the muscle attaches and the
  direction is the line whose moment arm it computed. A refused muscle is drawn in
  the refusal colour rather than omitted — a muscle that vanishes when the model
  cannot solve it looks like a muscle that is not there."
  [pose-data stature-m tensions]
  (let [by-name (into {} (map (juxt :name identity)) tensions)]
    (vec (for [m attachment/instances
               :let [{:keys [origin insertion dir length-m]}
                     (attachment/line-of-action pose-data stature-m m)]
               :when (and dir (> length-m 1e-6))
               :let [t (by-name (:name m))
                     pct (:mvc-pct t)]]
           {:label (:name m)
            :geo :cylinder
            :radius-m muscle-radius-m
            :mvc-pct pct
            :refused (:refused t)
            :antagonist? (:antagonist? t)
            :color (cond
                     (:antagonist? t) antagonist-rgb
                     (:refused t) refused-rgb
                     pct (ramp-rgb pct)
                     :else unloaded-rgb)
            :transform {:translation (math/vmid origin insertion)
                        :rotation (euler-for-direction dir)
                        :scale [1.0 length-m 1.0]}}))))

(def disc-stress-max-mpa
  "Top of the disc-stress ramp. Not a tolerance and not a threshold — a scale, so
  that two levels can be compared by eye. Disc tolerances are a clinical question
  and this actor does not answer clinical questions (G1)."
  1.2)

(defn disc-draws
  "A flat disc at each intervertebral level, tinted by its compressive stress.

  NOT DRAWN. Kept because the geometry is right and computing it costs nothing,
  but a disc's radius is about 24 mm at L5/S1 and the trunk it sits inside is
  drawn at 62 mm, so every one of these is hidden. Adding them to the frame would
  spend GPU slots on objects nobody can see, and — worse — would let a reader
  believe the picture shows the spine when it does not. The level profile is on
  the `#/spine` view, where it is a table and can be read."
  [pose-data body tensions]
  (vec (for [lvl spine/levels
             :let [{:keys [point axis]} (spine/level-point pose-data lvl)
                   row (spine/level-compression body pose-data tensions lvl)
                   frac (math/clamp (/ (:stress-mpa row) disc-stress-max-mpa) 0.0 1.0)]]
         {:label (:name lvl)
          :geo :cylinder
          :stress-mpa (:stress-mpa row)
          :force-n (:force-n row)
          :color (ramp-rgb (* 100.0 frac))
          :transform {:translation point
                      :rotation (euler-for-direction axis)
                      :scale [1.0 0.012 1.0]}
          :radius-m (Math/sqrt (/ (:disc-area-cm2 row) 3.14159 1e4))})))

(defn joint-draws
  "A small sphere at each anatomical landmark, so the chain reads as articulated
  rather than as a stack of separate rods."
  [pose-data]
  (mapv (fn [[k p]] {:label (name k)
                     :geo :sphere
                     :color [0.30 0.32 0.36]
                     :transform {:translation p :scale [1.0 1.0 1.0]}})
        (:joints pose-data)))

(defn bounds
  "Axis-aligned bounds of the placed chain, including the drawn thickness — the
  camera has to frame what is on screen, not the centre-lines."
  [pose-data]
  (let [pts (mapcat (fn [{:keys [proximal distal name]}]
                      (let [r (get bone-radius-m name 0.03)]
                        [(mapv - proximal [r r r]) (mapv + proximal [r r r])
                         (mapv - distal [r r r]) (mapv + distal [r r r])]))
                    (:segments pose-data))
        xs (map first pts) ys (map second pts)]
    {:min-x (apply min xs) :max-x (apply max xs)
     :min-y (apply min ys) :max-y (apply max ys)}))

(def base-azimuth-deg
  "Always shown from slightly off the sagittal plane, so the two arms of a
  bilateral body separate rather than overlapping."
  18.0)

(def extra-azimuth-deg
  "Additional swing, reached at 20 cm of left/right mismatch."
  22.0)

(def ^:private fov-rad
  "`kami.webgpu.mesh/view-projection` builds its perspective with a fixed vertical
  field of view of pi/3. Framing has to use the same number the executor uses; a
  camera that assumes a different FOV crops or shrinks by a factor nobody can see
  in the code."
  (/ math/pi 3.0))

(defn out-of-plane-extent
  "How far the chain reaches out of the sagittal plane (metres). Never zero since
  the model became bilateral — both arms hang at half the biacromial breadth from
  the midline — so this measures the width to FRAME, not the reason to rotate."
  [pose-data]
  (apply max 0.0 (map (fn [{:keys [proximal distal]}]
                        (max (Math/abs (nth proximal 2)) (Math/abs (nth distal 2))))
                      (:segments pose-data))))

(defn asymmetry-m
  "How far the left and right halves differ (metres), as the largest mismatch
  between a paired segment's centre of mass and its opposite number's mirror
  image.

  THIS, not the out-of-plane extent, is what a camera swing has to follow. Before
  the model was bilateral, reaching out of the sagittal plane and being asymmetric
  were the same thing; now the body is always out of plane and a symmetric posture
  is still perfectly readable head-on. Swinging for mere width would rotate every
  posture and make none of them comparable."
  [pose-data]
  (let [by (into {} (map (juxt :name identity)) (:segments pose-data))]
    (apply max 0.0
           (for [base ["upper_arm" "forearm" "hand"]
                 :let [l (by (str base "/left"))
                       r (by (str base "/right"))]
                 :when (and l r)
                 :let [[lx ly lz] (:com l) [rx ry rz] (:com r)]]
             (max (Math/abs (- lx rx)) (Math/abs (- ly ry))
                  (Math/abs (- lz (- rz))))))))

(defn camera
  "Framed on the chain's own bounds, and swung around it only when there is
  something out of plane to see.

  A sagittal camera shows every angle of a sagittal posture, which is why it is
  the default. But once abduction or lateral bend takes the chain out of the XY
  plane, a straight-on sagittal view foreshortens exactly the part that moved —
  the picture stops showing the input. The azimuth is derived from how far the
  body actually reaches out of plane rather than being a control, so a purely
  sagittal posture is never rotated away from the view that suits it.

  The distance comes from the bounds and the executor's own field of view, so the
  figure stays framed when the sliders make the body taller, shorter, or reach
  further forward."
  ([pose-data] (camera pose-data (/ 4.0 3.0)))
  ([pose-data aspect]
   (let [{:keys [min-x max-x min-y max-y]} (bounds pose-data)
         cx (/ (+ min-x max-x) 2.0)
         cy (/ (+ min-y max-y) 2.0)
         h (max 0.2 (- max-y min-y))
         w (max 0.2 (- max-x min-x))
         oop (out-of-plane-extent pose-data)
         ;; the out-of-plane reach has to be framed too, or abduction walks the
         ;; hand off the side of the picture
         extent (max h (/ (max w (* 2.0 oop)) (max 0.1 aspect)))
         dist (* 1.28 (/ (* 0.5 extent) (Math/tan (* 0.5 fov-rad))))
         dist (max 0.6 dist)
         ;; A fixed base angle, so the two arms separate instead of overlapping in
         ;; a head-on sagittal view, plus more with ASYMMETRY — which is the thing
         ;; a rotated view is needed to see. A symmetric posture always gets the
         ;; same angle, so two of them can be compared.
         asym (asymmetry-m pose-data)
         az (* (/ math/pi 180.0)
               (+ base-azimuth-deg
                  (* extra-azimuth-deg (math/clamp (/ asym 0.20) 0.0 1.0))))]
     {:eye [(+ cx (* dist (Math/sin az))) cy (* dist (Math/cos az))]
      :target [cx cy 0.0]
      :azimuth-deg (math/degrees az)
      :out-of-plane-m oop
      :asymmetry-m asym})))

(defn scene
  "The whole frame: body + posture + solved loads → draws + camera + legend.

  `tensions` is `suji.methods.muscle/solve-muscle-tensions` output; this namespace
  solves nothing itself. (`loads` is not taken: the colours are driven by %MVC, and
  a second, unused copy of the joint moments here would be a second chance to
  disagree with the readout.)"
  [body posture tensions]
  (let [p (pose/solve-pose body posture)]
    {:pose p
     :bones (bone-draws p tensions)
     :muscles (muscle-draws p (:stature-m body) tensions)
     ;; `disc-draws` exists and is correct, and is deliberately not in the frame —
     ;; see its docstring
     :discs []
     :joints (joint-draws p)
     :camera (camera p)
     :legend (mapv (fn [{:keys [band max-mvc-pct rgb]}]
                     {:band band :max-mvc-pct max-mvc-pct :rgb rgb})
                   load-bands)}))

(defn solve-and-scene
  "Convenience: posture → loads → tensions → scene, in one call, using suji for
  every number. Returns {:loads :tensions :scene}."
  [body posture]
  (let [loads (load/solve-posture-loads body posture)
        tensions (muscle/solve-muscle-tensions body posture loads)]
    {:loads loads
     :tensions tensions
     :scene (scene body posture tensions)}))
