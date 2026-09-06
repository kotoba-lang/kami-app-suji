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
      (is (= (take 2 eye) (take 2 target)) "the camera looks straight down -Z")
      (is (<= (- cy half-h) min-y) (str (:name w) " @" aspect ": bottom is cut off"))
      (is (>= (+ cy half-h) max-y) (str (:name w) " @" aspect ": top is cut off"))
      (is (<= (- cx half-w) min-x) (str (:name w) " @" aspect ": front is cut off"))
      (is (>= (+ cx half-w) max-x) (str (:name w) " @" aspect ": back is cut off")))))

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
