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
  from, expressed as the highest %MVC among the muscles that cross that joint —
  a mechanical quantity, force divided by maximum voluntary force.

  ⚠ IT IS NOT THE TABLE の QUANTITY, and this docstring claimed it was until
  2026-09-07: it said the bands here were the same ones `strain/stiffness-band`
  uses for text, so the picture and the table could not say different things. They
  say different things at almost every load. Measured at a 120-minute session:

      9 %MVC   picture moderate   table very-high
     15 %MVC   picture high       table very-high
      5 %MVC   picture moderate   table low

  The picture bands an INSTANTANEOUS load and the table bands an ACCUMULATED DOSE,
  which depends on how long the posture is held; at 120 minutes a muscle at
  9 %MVC has a high dose and a low instantaneous load, and both statements are
  true. The defect was never the colours — it was sharing the four words
  low/moderate/high/very-high between two quantities and then asserting they could
  not disagree. The legend now carries which quantity it bands.

  NON-DIAGNOSTIC (G1): a colour is not a finding, and there is no colour in this
  file that means anything clinical."
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
  {"head_neck"      #{"cervical_extensors" "scalenes" "nuchal_ligament"}
   "thorax_abdomen" #{"erector_spinae" "quadratus_lumborum" "obliques"
                      "posterior_lumbar_ligaments"}
   "upper_arm"      #{"anterior_deltoid" "middle_deltoid" "latissimus_dorsi"
                      "upper_trapezius" "middle_trapezius" "levator_scapulae"}
   ;; the forearm hangs off the ELBOW and the hand off the WRIST — the shoulder
   ;; carries them too, but the joint a segment is attached to is the one whose
   ;; load its colour should report
   "forearm"        #{"biceps_brachii" "brachialis" "triceps_brachii"}
   "hand"           #{"wrist_extensors" "wrist_flexors"}
   "pelvis"         #{}})

;; --- the load ramp -----------------------------------------------------------
;; Linear RGB for the GPU, not CSS: these never reach a stylesheet, so the
;; design-system token contract does not apply to them (it governs app CSS). The
;; breakpoints are %MVC. They are NOT `strain/stiffness-band`'s breakpoints — that
;; function bands a session-dependent dose in [0,1] at 0.20/0.45/0.70, and this
;; bands an instantaneous load in percent at 5/15/30. Two scales, two quantities,
;; and this comment used to say they were one.
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

(def ligament-load-floor-n
  "Above this much ligament force, a segment is being held by tissue rather than
  by muscle and must not be drawn as though the muscles' quiet meant ease."
  200.0)

(def maximum-voluntary-pct
  "Where the top of the colour ramp anchors: 100 %MVC.

  The top band's `:max-mvc-pct` is infinite, which is right for BANDING — there is
  no band above `very-high` — and wrong for a ramp, because an infinite span makes
  the interpolation coefficient zero and every value above 30 %MVC comes out as
  exactly one colour. Measured 2026-09-07 with the corrected lumbar moment: the
  trunk read heat 0.53 at 15°, 30°, 45° AND 60° of flexion, so the picture could
  not tell 46.8 %MVC from 83.6 %MVC from 43.7 %MVC.

  100 is not a chosen constant. It is the maximum voluntary contraction — the one
  bound in this domain that is not arbitrary — and `muscle.cljc` deliberately does
  not clamp there, so values above it exist and stay distinguishable at the ramp's
  end rather than being folded into it."
  100.0)

(def ligament-rgb
  "Held by ligament, not by muscle. Distinct from every load colour and from the
  refusal colour: the load is real and computed — it is simply not muscular, and
  the muscles' silence is the finding rather than relief."
  [0.72 0.45 0.72])

(def refused-rgb
  "A segment whose load the model declined to compute. Deliberately unlike both
  ends of the load ramp: it must not read as 'fine' or as 'bad', because it is
  neither — it is 'not answered'."
  [0.42 0.35 0.62])

(defn band-for
  "The load band a %MVC falls in, or nil when there is no %MVC.

  A LIGAMENT has none — it cannot contract, so there is no maximum voluntary
  contraction to be a fraction of — and this threw on the nil until 2026-09-06.
  Returning nil rather than the lowest band matters: the lowest band is `low`, and
  a structure carrying 1,500 N would have been labelled the least loaded thing on
  the page."
  [mvc-pct]
  (when (number? mvc-pct)
    (first (filter #(< mvc-pct (:max-mvc-pct %)) load-bands))))

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
        top? (not (math/finite? (:max-mvc-pct this)))
        ;; The top band has no band above it, so it interpolates toward a MORE
        ;; SATURATED red of its own across the rest of the voluntary range instead
        ;; of standing still. Saturating rather than darkening, because the
        ;; picture is read as red-minus-green and darkening lowers both channels
        ;; together — measured: a deeper red made 76.6 %MVC read COOLER than
        ;; 46.8 %MVC, which is the same inversion this whole state exists to
        ;; prevent, arriving through the other door. See `maximum-voluntary-pct`.
        span (if top?
               (- maximum-voluntary-pct prev-max)
               (- (:max-mvc-pct this) prev-max))
        target (if top?
                 (let [[r g b] (:rgb this)] [(min 1.0 (* 1.05 r)) (* 0.15 g) (* 0.15 b)])
                 (:rgb nxt))
        t (math/clamp (/ (- mvc-pct prev-max) (max 1e-9 span)) 0.0 1.0)]
    (mapv #(lerp %1 %2 (* 0.65 t)) (:rgb this) target)))

(defn segment-state
  "What this segment's colour is allowed to claim.

  Three outcomes, and they are NOT interchangeable:
    {:kind :loaded  :mvc-pct n}  — a muscle acts here and the model solved it
    {:kind :none}                — no solved muscle acts about this joint (the pelvis)
    {:kind :refused :names [..]} — a muscle acts here and the model DECLINED to
                                   compute its force at this posture
    {:kind :ligament ...}        — the LIGAMENTS are carrying it and the muscles
                                   have gone quiet

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
        ligaments (filter :ligament? mine)
        vals (keep :mvc-pct mine)]
    (cond
      (empty? mine) {:kind :none}
      (seq refused) {:kind :refused :names (mapv :name refused)}

      ;; FLEXION-RELAXATION MUST NOT READ AS RELIEF. In deep trunk flexion the
      ;; erector spinae falls silent and the posterior ligaments take the load —
      ;; so colouring by muscle %MVC alone makes the segment go COOLER exactly
      ;; where the spine is most loaded. Measured 2026-09-06: the trunk's heat
      ;; went 0.39 -> 0.53 -> 0.10 across 15/30/45 deg of flexion, and the picture
      ;; said the worst posture was the easiest.
      ;; THE CONDITION IS WHICH TISSUE CARRIES MORE, not whether the muscle went
      ;; quiet. It used to be `max %MVC < 8`, which was true only because
      ;; `lumbosacral-moment` understated the demand by about half: with the
      ;; moment corrected on 2026-09-07 the erector spinae is still at 43.7 %MVC
      ;; at 60° of flexion, so the "quiet" test could never fire again and this
      ;; whole state became unreachable — a colour nothing could ever be.
      ;;
      ;; What survived the correction is the SHIFT. Measured at
      ;; `laptop-on-lap` + trunk flexion: ligament 429 N against erector spinae
      ;; 1398 N at 30°, then 1572 against 1367 at 45°, then 3989 against 635 at
      ;; 60°. The crossing is the finding, and it is what a reader needs to see,
      ;; because it changes what would unload the spine.
      (and (seq ligaments)
           (> (reduce + 0.0 (keep :force-n ligaments)) ligament-load-floor-n)
           (> (reduce + 0.0 (keep :force-n ligaments))
              (reduce + 0.0 (keep :force-n (remove :ligament? mine)))))
      {:kind :ligament
       :mvc-pct (when (seq vals) (apply max vals))
       :ligament-n (reduce + 0.0 (keep :force-n ligaments))
       :muscle-n (reduce + 0.0 (keep :force-n (remove :ligament? mine)))
       :names (mapv :name ligaments)}

      (seq vals) {:kind :loaded :mvc-pct (apply max vals)}
      :else {:kind :none})))

;; --- geometry placement ------------------------------------------------------
;; Every generator in `kami.webgpu.geometry` is built along +Y and centred, so a
;; bone is that shape scaled to its length, rotated about Z onto its direction and
;; translated to its midpoint. The pose already carries the rotation as `:euler-z`,
;; so this is placement, not trigonometry.
;;
;; A BONE IS TESSELLATED AT ITS OWN SIZE, NOT AT UNIT SIZE AND SCALED. That is a
;; change from how the cylinder was drawn, and it was forced by measurement.
;;
;; The cylinder could be built once at radius 1 / height 1 and scaled by
;; [r, L, r], because a cylinder's side normals are purely radial and its cap
;; normals are purely axial: an anisotropic scale leaves both correct by accident.
;; An anatomical bone has neither property. Two things break:
;;
;;   1. Its normals. Under a scale of [0.032, 0.31, 0.032] a normal has to be
;;      transformed by the inverse transpose, and `mesh/model-matrix` does not do
;;      that. The condylar flares — the whole point of this shape — are exactly
;;      the surfaces whose normals have a large axial component, so they are
;;      exactly the ones a 10:1 anisotropic scale would light wrongly.
;;   2. Its topology. `loft` splits a ring into a hard edge when the two bands
;;      meet more sharply than 60°, and how sharp that is depends on the ASPECT
;;      RATIO. Measured 2026-09-07 with the humerus parameters: at unit aspect the
;;      mesh has 378 vertices, at the real 0.31 m × 0.032 m it has 357 — one ring
;;      creases at unit aspect that does not crease on a real humerus. So the unit
;;      template is not the sized mesh, and drawing it scaled would put hard
;;      shading edges where the real bone is smooth.
;;
;; Tessellating per frame is not affordable either — stature is a live slider and
;; each rebuild is a GPU upload. So the length is BUCKETED geometrically: at most
;; one mesh per 5% of length, which bounds a full sweep of the stature slider to
;; about nine meshes per bone instead of one per frame, and leaves a residual
;; scale of under 2.5% on one axis. `bone-mesh-length-m` below is that bucket, and
;; it is here in the pure layer so it can be asserted without a GPU.

(def bone-mesh-length-step
  "Geometric bucket ratio for bone mesh lengths. 1.05 = a new mesh every 5%."
  1.05)

(defn bone-mesh-length-m
  "The length a bone's mesh is actually built at: `length-m` rounded to the
  nearest `bone-mesh-length-step` power. The draw then scales y by the leftover
  ratio, which is bounded by ±2.47% — small enough that the normals it does not
  correct are within about 1.5° of true."
  [length-m]
  (let [l (max 1e-4 (double length-m))
        k (Math/round (/ (Math/log l) (Math/log bone-mesh-length-step)))]
    (Math/pow bone-mesh-length-step k)))

(def bone-radius-m
  "Drawn thickness per segment (metres). Visual only — the physics is a line-mass
  model and carries no cross-section, so this is honestly decoration and is stated
  here rather than hidden in a shader."
  {"pelvis" 0.055 "thorax_abdomen" 0.062 "head_neck" 0.048
   "upper_arm" 0.032 "forearm" 0.026 "hand" 0.020
   ;; the lower limb, for whenever suji grows one
   "thigh" 0.042 "shank" 0.033 "foot" 0.024})

(def bone-shapes
  "Shape catalogue: a kind → the generator to call and the proportions to call it
  with. Pure data — the app does no geometry arithmetic, it hands these straight
  to `kami.webgpu.geometry`, which owns every number that has to be derived.

  The parameters here are anthropometry, not tessellation: how far an epiphysis
  flares, over what fraction of the length, how oval the section is, how much the
  shaft bows. A femur, a humerus and a phalanx are the same generator with
  different values of those four, which is why there is one `:long-bone` entry per
  bone rather than one function per bone.

  `:length` and `:shaft-radius`/`:radius` are deliberately absent: they are 1.0,
  and the draw transform supplies the real ones (see the note above)."
  {:humerus        {:generator :long-bone
                    :params {:sectors 20 :proximal-flare 1.55 :distal-flare 1.75
                             :epiphysis-frac 0.16 :flatten 0.88 :bow 0.10}}
   :radius-ulna    {:generator :long-bone
                    :params {:sectors 20 :proximal-flare 1.7 :distal-flare 1.35
                             :epiphysis-frac 0.13 :flatten 0.72 :bow 0.22}}
   :femur          {:generator :long-bone
                    :params {:sectors 20 :proximal-flare 1.85 :distal-flare 2.05
                             :epiphysis-frac 0.17 :flatten 0.92 :bow 0.28}}
   :tibia          {:generator :long-bone
                    :params {:sectors 20 :proximal-flare 1.9 :distal-flare 1.4
                             :epiphysis-frac 0.15 :flatten 0.78 :bow 0.08}}
   ;; a hand or a foot is a bundle of short bones; drawn as one, it is a stubby
   ;; long bone that is much wider than it is deep
   :short-bone     {:generator :long-bone
                    :params {:sectors 16 :proximal-flare 1.35 :distal-flare 1.25
                             :epiphysis-frac 0.26 :flatten 0.5 :bow 0.0}}
   :vertebral-column {:generator :vertebral-body
                      :params {:sectors 20 :endplate-flare 1.22 :waist 0.78
                               :posterior-flatten 0.6}}
   :pelvic-block   {:generator :vertebral-body
                    :params {:sectors 20 :endplate-flare 1.45 :waist 0.72
                             :posterior-flatten 0.78}}
   :cranium        {:generator :vertebral-body
                    :params {:sectors 20 :endplate-flare 1.3 :waist 0.92
                             :posterior-flatten 0.85}}
   ;; the fallback. suji is growing segments (a lower limb, a scapulothoracic
   ;; element) and a name this app has never heard of must get a bone, not a
   ;; crash and not an invisible draw.
   :generic-long-bone {:generator :long-bone
                       :params {:sectors 16 :proximal-flare 1.5 :distal-flare 1.5
                                :epiphysis-frac 0.15 :flatten 0.85 :bow 0.0}}})

(def default-bone-shape
  "What an unrecognised segment is drawn as. Named rather than inlined so the test
  that a new segment still gets a bone can assert the same thing the code uses."
  :generic-long-bone)

(def bone-shape-by-segment
  "Segment base name → a key of `bone-shapes`. Axial segments are drawn as the
  vertebral column they are: this model's trunk IS its spine, and the discs it
  reports are the joints between these."
  {"pelvis" :pelvic-block
   "thorax_abdomen" :vertebral-column
   "head_neck" :cranium
   "upper_arm" :humerus
   "forearm" :radius-ulna
   "hand" :short-bone
   "thigh" :femur
   "shank" :tibia
   "foot" :short-bone})

(defn bone-shape
  "The shape key for a segment base name. Total: an unknown name falls back rather
  than returning nil, because a nil shape is a bone that silently does not draw."
  [base]
  (get bone-shape-by-segment base default-bone-shape))

(defn bone-draws
  "One draw per placed segment."
  [pose-data tensions]
  (mapv (fn [{:keys [name base side proximal distal length-m euler-z] :as placed}]
          (let [mid (math/vmid proximal distal)
                {:keys [kind mvc-pct] :as st} (segment-state tensions base side)]
            {:label name
             :geo (bone-shape base)
             :radius-m (get bone-radius-m base 0.03)
             ;; what mesh to build, as opposed to where to put it. The viewport
             ;; caches on this map, so a bone only re-tessellates when its own
             ;; size leaves the bucket.
             :mesh {:kind (bone-shape base)
                    :length-m (bone-mesh-length-m length-m)
                    :radius-m (get bone-radius-m base 0.03)}
             :state kind
             :refused-names (:names st)
             :mvc-pct mvc-pct
             :band (when mvc-pct (:band (band-for mvc-pct)))
             :ligament-n (:ligament-n st)
             ;; carried alongside, because the :ligament state is now decided by
             ;; comparing the two and a reader of the draw should be able to check
             ;; that comparison rather than take it on trust
             :muscle-n (:muscle-n st)
             :color (case kind
                      :loaded (ramp-rgb mvc-pct)
                      :ligament ligament-rgb
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
  (let [pts (mapcat (fn [{:keys [proximal distal base]}]
                      ;; keyed by `:base`, the way `bone-draws` keys it. It used to
                      ;; key by `:name`, which is the INSTANCE (`upper_arm/left`)
                      ;; while `bone-radius-m` is a table of segments — so every
                      ;; paired limb missed and fell to the 0.03 default while
                      ;; being drawn at 0.032. The camera framed a body 2 mm
                      ;; thinner at the upper arm than the one on screen, under a
                      ;; docstring saying it frames what is on screen.
                      (let [r (get bone-radius-m base 0.03)]
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
     ;; `:quantity` travels with the legend because the four band words are also
     ;; the dose's band words, and a legend that does not say which quantity it is
     ;; banding invites the reader to compare a picture against a table that is
     ;; measuring something else.
     :legend {:quantity :mvc-pct
              :bands (mapv (fn [{:keys [band max-mvc-pct rgb]}]
                             {:band band :max-mvc-pct max-mvc-pct :rgb rgb})
                           load-bands)}}))

(defn solve-and-scene
  "Convenience: posture → loads → tensions → scene, in one call, using suji for
  every number. Returns {:loads :tensions :scene}."
  [body posture]
  (let [loads (load/solve-posture-loads body posture)
        tensions (muscle/solve-muscle-tensions body posture loads)]
    {:loads loads
     :tensions tensions
     :scene (scene body posture tensions)}))
