(ns kami.app-suji.scene-test
  "The scene layer, checked without a browser.

  What is worth asserting here is not that a cylinder appears — the browser check
  does that — but that the picture and the numbers are the SAME claim: a segment
  the physics says is loaded has to be a colour the legend calls loaded, and the
  camera has to frame what is actually on screen."
  (:require [clojure.test :refer [deftest is]]
            [kami.app-suji.core :as core]
            [kami.app-suji.scene :as scene]
            [suji.methods.math :as math]
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]))

(def ^:private body (segment/build-body 70.0 1.70))
(defn- for-ws [w] (scene/solve-and-scene body (posture/posture-from-workstation w)))

(deftest every-placed-segment-gets-exactly-one-draw
  (let [{:keys [scene]} (for-ws posture/laptop-on-lap)]
    (is (= (count (get-in scene [:pose :segments])) (count (:bones scene))))
    (is (= (set (map :name (get-in scene [:pose :segments])))
           (set (map :label (:bones scene)))))
    (is (seq (:joints scene)))))

(deftest a-bone-with-no-muscle-is-not-drawn-as-unloaded
  ;; nil is not zero. The pelvis has no solved muscle acting about it, and must be
  ;; shown in the neutral colour rather than in the "low load" green — the picture
  ;; would otherwise assert something the model never computed.
  (let [{:keys [scene]} (for-ws posture/laptop-on-lap)
        pelvis (first (filter #(= "pelvis" (:label %)) (:bones scene)))]
    (is (nil? (:mvc-pct pelvis)))
    (is (nil? (:band pelvis)))
    (is (= scene/unloaded-rgb (:color pelvis)))
    (is (not= (scene/ramp-rgb 0.0) (:color pelvis))
        "the base segment must be distinguishable from a segment at zero load")))

(deftest colour-tracks-load-monotonically
  ;; the ramp has to be ordered: more load can never be drawn greener. Compared on
  ;; the red-minus-green channel, which is what the eye reads off this ramp.
  (let [heat (fn [pct] (let [[r g _] (scene/ramp-rgb pct)] (- r g)))
        pcts [0.5 3.0 8.0 14.0 20.0 28.0 40.0 80.0]]
    (is (every? (fn [[a b]] (<= (heat a) (heat b))) (partition 2 1 pcts))
        (str "ramp must not go backwards: " (mapv heat pcts)))))

(deftest bands-agree-with-the-legend
  (let [{:keys [scene]} (for-ws posture/laptop-on-lap)]
    (is (= (mapv :band scene/load-bands) (mapv :band (:legend scene))))
    (doseq [b (remove #(nil? (:mvc-pct %)) (:bones scene))]
      (is (= (:band b) (:band (scene/band-for (:mvc-pct b))))
          (str (:label b) ": the drawn band and the legend's band must agree")))))

(deftest the-picture-uses-the-same-numbers-as-the-table
  ;; the failure this prevents: a view that recomputes its own loads and drifts
  ;; from the readout beside it
  (let [{:keys [tensions scene]} (for-ws posture/laptop-on-lap)
        head-bone (first (filter #(= "head_neck" (:label %)) (:bones scene)))
        cerv (first (filter #(= "cervical_extensors" (:name %)) tensions))]
    (is (math/nearly= (:mvc-pct cerv) (:mvc-pct head-bone) 1e-12))))

(deftest camera-frames-the-whole-body
  ;; a camera that crops the head is a correct-looking failure. Check that every
  ;; drawn point falls inside the frustum's half-extent at the body's plane.
  (doseq [w posture/reference-workstations
          aspect [1.0 (/ 4.0 3.0) 2.0]]
    (let [{:keys [scene]} (for-ws w)
          p (:pose scene)
          {:keys [eye target]} (scene/camera p aspect)
          {:keys [min-x max-x min-y max-y]} (scene/bounds p)
          dist (math/vlen (math/v- eye target))
          half-h (* dist (Math/tan (/ math/pi 6.0)))   ;; fov/2 = pi/6
          half-w (* half-h aspect)
          cy (second eye)]
      (is (math/nearly= scene/base-azimuth-deg (:azimuth-deg (scene/camera p aspect)) 1e-9)
          "a symmetric posture always gets the same angle, so two can be compared")
      (is (<= (- cy half-h) min-y) (str (:name w) " @" aspect ": bottom is cut off"))
      (is (>= (+ cy half-h) max-y) (str (:name w) " @" aspect ": top is cut off"))
      ;; horizontal framing now has to cover the body's WIDTH as well as its
      ;; depth, since both arms are placed; the vertical bound is the tight one
      (is (>= (* 2.0 half-w) (- max-x min-x)) (str (:name w) " @" aspect ": too narrow")))))

(deftest a-refused-segment-is-neither-green-nor-red
  ;; With the trunk bent hard sideways, all three of this model's girdle
  ;; suspenders on the raised side would pull the shoulder DOWN, and `suji`
  ;; declines to give them a force. A real shoulder there rests on the ribcage,
  ;; which this model has no element for. The picture must decline too: green
  ;; would say the posture is easy, red would say it is hard, and the model said
  ;; neither.
  ;;
  ;; The trigger has moved twice as the model improved — first away from 90° of
  ;; shoulder flexion when wrapping surfaces landed, then away from a folded head
  ;; when the middle trapezius did. The behaviour under test has not.
  (let [state (-> core/initial-state
                  (assoc-in [:posture :trunk-flexion-deg] 60.0)
                  (assoc-in [:posture :trunk-lateral-bend-deg] 40.0))
        {:keys [tensions scene]} (core/solved state)
        refused (remove :antagonist? (filter :refused tensions))
        arm-bone (first (filter #(= "upper_arm/right" (:label %)) (:bones scene)))]
    (is (seq refused) "the premise of this test: suji refuses somebody here")
    (is (= :refused (:state arm-bone)))
    (is (= scene/refused-rgb (:color arm-bone)))
    (is (nil? (:mvc-pct arm-bone)) "a refused segment has no %MVC to show")
    (is (not= scene/refused-rgb scene/unloaded-rgb)
        "'not answered' must be distinguishable from 'no muscle here'")
    (is (not= scene/refused-rgb scene/antagonist-rgb)
        "and from 'not being asked', which is a different thing again")
    (doseq [pct [0.0 5.0 20.0 60.0 120.0]]
      (is (not= scene/refused-rgb (scene/ramp-rgb pct))
          (str "the refusal colour must not collide with the load ramp at " pct "%")))))

(deftest an-antagonist-does-not-make-a-segment-look-unanswered
  ;; For a mirror-paired task exactly one side resists and the other is refused as
  ;; its antagonist. Colouring the segment purple for that would mark most of the
  ;; body unanswerable in any posture with a frontal component.
  (let [state (assoc-in core/initial-state [:posture :shoulder-abduction-deg] 45.0)
        {:keys [tensions scene]} (core/solved state)]
    (is (some :antagonist? tensions) "the premise: this posture has antagonists")
    (doseq [b (:bones scene)]
      (is (not= :refused (:state b))
          (str (:label b) ": an antagonist must not read as an unanswered load")))))

(deftest the-wrapped-and-the-covered-joints-are-no-longer-refused
  ;; two postures that used to come back unanswerable, kept as tests because the
  ;; wrapping surfaces and the middle trapezius are load-bearing for this app
  (doseq [[label st] [["90° shoulder flexion"
                       (-> core/initial-state
                           (assoc-in [:posture :shoulder-flexion-deg] 90.0)
                           (assoc-in [:posture :elbow-flexion-deg] 0.0))]
                      ["head and trunk folded"
                       (-> core/initial-state
                           (assoc-in [:posture :head-flexion-deg] 60.0)
                           (assoc-in [:posture :trunk-flexion-deg] 60.0))]]]
    (let [{:keys [tensions loads]} (core/solved st)]
      (is (:complete? (muscle/tension-summary tensions loads))
          (str label " must now be answerable")))))

(deftest the-summary-does-not-call-an-incomplete-answer-complete
  (let [complete (core/solved core/initial-state)
        refused (core/solved (-> core/initial-state
                                 (assoc-in [:posture :trunk-flexion-deg] 60.0)
                                 (assoc-in [:posture :trunk-lateral-bend-deg] 40.0)))]
    (is (:complete? (muscle/tension-summary (:tensions complete) (:loads complete))))
    (is (not (:complete? (muscle/tension-summary (:tensions refused) (:loads refused)))))))

(deftest out-of-plane-input-reaches-the-picture
  ;; the frontal plane is new; a control that changes nothing on screen is a lie
  (let [flat (core/solved core/initial-state)
        abducted (core/solved (assoc-in core/initial-state
                                        [:posture :shoulder-abduction-deg] 60.0)) 
        y-of (fn [r label] (nth (:com (first (filter #(= label (:name %))
                                                     (get-in r [:scene :pose :segments])))) 1))]
    ;; abduction lifts each hand relative to its own shoulder — the sagittal
    ;; z-check this replaced stopped meaning anything once both arms were placed
    (is (> (y-of abducted "hand/left") (y-of flat "hand/left"))
        "abduction must raise the hand")
    (is (math/nearly= (y-of abducted "hand/left") (y-of abducted "hand/right") 1e-9)
        "and must do it symmetrically")))

(deftest the-camera-swings-with-asymmetry-not-with-width
  ;; Before the model was bilateral, reaching out of the sagittal plane and being
  ;; asymmetric were the same thing. Now the body is ALWAYS out of plane — both
  ;; arms hang off the midline — and a symmetric posture is still readable head-on.
  ;; Swinging for mere width would rotate every posture and make none of them
  ;; comparable, which is what happened when this test first failed.
  (let [sym (get-in (core/solved core/initial-state) [:scene :camera])
        bent (get-in (core/solved (assoc-in core/initial-state
                                            [:posture :trunk-lateral-bend-deg] 30.0))
                     [:scene :camera])]
    (is (> (:out-of-plane-m sym) 0.1) "a bilateral body is always out of plane")
    (is (math/nearly= 0.0 (:asymmetry-m sym) 1e-9) "but a symmetric posture is symmetric")
    (is (math/nearly= scene/base-azimuth-deg (:azimuth-deg sym) 1e-9))
    (is (> (:asymmetry-m bent) 0.05) "leaning sideways makes the two halves differ")
    (is (> (:azimuth-deg bent) (:azimuth-deg sym)) "and that is what earns extra swing")
    (is (<= (:azimuth-deg bent) (+ scene/base-azimuth-deg scene/extra-azimuth-deg))
        "the swing is bounded")))

(deftest leaning-further-forward-reddens-the-trunk
  (let [heat (fn [flex]
               (let [state (-> core/initial-state
                               (assoc-in [:posture :trunk-flexion-deg] flex))
                     {:keys [scene]} (core/solved state)
                     t (first (filter #(= "thorax_abdomen" (:label %)) (:bones scene)))
                     [r g _] (:color t)]
                 (- r g)))
        xs (mapv heat [0.0 15.0 30.0 45.0])]
    (is (every? (fn [[a b]] (<= a b)) (partition 2 1 xs))
        (str "the trunk must not get cooler as it leans further: " xs))))
