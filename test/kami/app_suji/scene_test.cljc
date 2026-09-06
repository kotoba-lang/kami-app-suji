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
          dist (nth eye 2)
          half-h (* dist (Math/tan (/ math/pi 6.0)))   ;; fov/2 = pi/6
          half-w (* half-h aspect)
          [cx cy] eye]
      (is (math/nearly= 0.0 (:azimuth-deg (scene/camera p aspect)) 1e-9)
          "a sagittal posture must not be rotated away from the sagittal view")
      (is (= (take 2 eye) (take 2 target)) "the camera looks straight down -Z")
      (is (<= (- cy half-h) min-y) (str (:name w) " @" aspect ": bottom is cut off"))
      (is (>= (+ cy half-h) max-y) (str (:name w) " @" aspect ": top is cut off"))
      (is (<= (- cx half-w) min-x) (str (:name w) " @" aspect ": front is cut off"))
      (is (>= (+ cx half-w) max-x) (str (:name w) " @" aspect ": back is cut off")))))

(deftest a-refused-segment-is-neither-green-nor-red
  ;; At 90° of shoulder flexion this model's straight-line anterior deltoid passes
  ;; through the joint it acts about, and `suji` declines to compute its force.
  ;; The picture must decline too: colouring the arm green would say the posture is
  ;; easy, colouring it red would say it is hard, and the model said neither.
  (let [state (-> core/initial-state
                  (assoc-in [:posture :shoulder-flexion-deg] 90.0)
                  (assoc-in [:posture :elbow-flexion-deg] 0.0))
        {:keys [tensions scene]} (core/solved state)
        deltoid (first (filter #(= "anterior_deltoid" (:name %)) tensions))
        arm-bone (first (filter #(= "upper_arm" (:label %)) (:bones scene)))]
    (is (:refused deltoid)
        "the premise of this test: suji refuses the deltoid at 90° shoulder flexion")
    (is (= :refused (:state arm-bone)))
    (is (= scene/refused-rgb (:color arm-bone)))
    (is (nil? (:mvc-pct arm-bone)) "a refused segment has no %MVC to show")
    (is (not= scene/refused-rgb scene/unloaded-rgb)
        "'not answered' must be distinguishable from 'no muscle here'")
    (doseq [pct [0.0 5.0 20.0 60.0 120.0]]
      (is (not= scene/refused-rgb (scene/ramp-rgb pct))
          (str "the refusal colour must not collide with the load ramp at " pct "%")))))

(deftest the-summary-does-not-call-an-incomplete-answer-complete
  (let [complete (core/solved core/initial-state)
        refused (core/solved (-> core/initial-state
                                 (assoc-in [:posture :shoulder-flexion-deg] 90.0)
                                 (assoc-in [:posture :elbow-flexion-deg] 0.0)))]
    (is (:complete? (muscle/tension-summary (:tensions complete))))
    (is (not (:complete? (muscle/tension-summary (:tensions refused)))))))

(deftest out-of-plane-input-reaches-the-picture
  ;; the frontal plane is new; a control that changes nothing on screen is a lie
  (let [flat (core/solved core/initial-state)
        abducted (core/solved (assoc-in core/initial-state
                                        [:posture :shoulder-abduction-deg] 60.0)) 
        z-of (fn [r label] (nth (:com (first (filter #(= label (:name %))
                                                     (get-in r [:scene :pose :segments])))) 2))]
    (is (math/nearly= 0.0 (z-of flat "hand") 1e-9) "a sagittal posture stays in the plane")
    (is (> (Math/abs (z-of abducted "hand")) 0.05)
        "abduction must carry the hand out of the sagittal plane")))

(deftest the-camera-swings-only-when-there-is-something-out-of-plane-to-see
  ;; A control that changes the model but not the picture is a control the reader
  ;; cannot use; a camera that swings for a posture with nothing out of plane is a
  ;; picture the reader cannot compare. Both directions are checked.
  (let [flat (get-in (core/solved core/initial-state) [:scene :camera])
        bent (get-in (core/solved (assoc-in core/initial-state
                                            [:posture :shoulder-abduction-deg] 60.0))
                     [:scene :camera])]
    (is (math/nearly= 0.0 (:out-of-plane-m flat) 1e-9))
    (is (math/nearly= 0.0 (:azimuth-deg flat) 1e-9))
    (is (> (:out-of-plane-m bent) 0.05))
    (is (> (:azimuth-deg bent) 5.0) "an abducted posture has to be shown from an angle")
    (is (<= (:azimuth-deg bent) 40.0) "and the swing is bounded")))

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
