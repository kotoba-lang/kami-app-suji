(ns kami.app-suji.viewport
  "The 3-D viewport: the only namespace in this app that touches a GPU.

  It owns no geometry and no camera maths. `kami.webgpu.geometry` tessellates the
  primitives, `kami.app-suji.scene` places them, and `kami.webgpu.mesh` executes —
  including the WebGPU→WebGL 2.0 fallback, which `init-canvas!` performs internally,
  so this app never inspects `navigator.gpu` itself.

  Two meshes are uploaded ONCE at init (a unit cylinder and a small sphere) and
  re-drawn per frame with a different transform each. Re-uploading per frame would
  make every slider move allocate GPU buffers for geometry that never changed."
  (:require [kami.app-suji.scene :as scene]
            [kami.webgpu.geometry :as geom]
            [kami.webgpu.mesh :as mesh]))

(defonce state (atom {:viewport nil :meshes nil :backend nil}))

(def ^:private unit-cylinder
  "Radius 1, height 1, along +Y and centred — `scene` scales it to each bone."
  (geom/cylinder 1.0 1.0 20))

(def ^:private joint-sphere (geom/sphere 1.0 10 16))

;; ONE UPLOAD PER DRAWN OBJECT, not one per shape.
;;
;; `mesh/render-scene!` reads its per-draw uniforms — the MVP and the colour —
;; out of the buffer handle that came back from `upload-mesh!`, writing them with
;; `write-buffer!` as it encodes each draw. Every draw in the pass is encoded
;; before any of them executes, so N draws that share one uploaded mesh all read
;; the LAST uniform written: they land on top of each other and the frame shows
;; one object. Measured 2026-09-06 — the six bones rendered as a single rod, with
;; no error and a plausible-looking picture, which is the worst way for this to
;; fail. The buffers are per-slot, uploaded once at init and re-transformed each
;; frame; the geometry itself is still tessellated exactly twice.
(def ^:private bone-slots 12)
(def ^:private joint-slots 12)
(def ^:private muscle-slots 20)
(def ^:private disc-slots 12)

(defn- upload! [viewport]
  (let [ctx (:mesh-context viewport)]
    {:bones (vec (repeatedly bone-slots #(mesh/upload-mesh! ctx unit-cylinder)))
     :joints (vec (repeatedly joint-slots #(mesh/upload-mesh! ctx joint-sphere)))
     :muscles (vec (repeatedly muscle-slots #(mesh/upload-mesh! ctx unit-cylinder)))
     :discs (vec (repeatedly disc-slots #(mesh/upload-mesh! ctx unit-cylinder)))}))

(defn- draws-for
  "scene → the draw list `render-scene!` consumes. The unit cylinder has radius 1,
  so a bone's drawn thickness is a scale on X and Z; its length is the scale on Y,
  which `scene` already put in the transform.

  A draw with no slot is DROPPED and reported rather than silently overlapping an
  earlier one — see the note above. If this ever fires, raise the slot counts."
  [{:keys [bones joints muscles discs]} meshes]
  (doseq [[label n cap] [["bones" (count bones) bone-slots]
                         ["joints" (count joints) joint-slots]
                         ["muscles" (count muscles) muscle-slots]
                         ["discs" (count discs) disc-slots]]]
    (when (> n cap)
      (js/console.error "kami-app-suji:" n label "but only" cap "GPU slots — raise the slot count")))
  (concat
   (map (fn [{:keys [color transform radius-m]} buffers]
          (let [[sx sy sz] (:scale transform)]
            {:buffers buffers
             :color color
             :transform (assoc transform :scale [(* sx radius-m) sy (* sz radius-m)])}))
        bones (:bones meshes))
   (map (fn [{:keys [color transform]} buffers]
          {:buffers buffers
           :color color
           :transform (assoc transform :scale [0.026 0.026 0.026])})
        joints (:joints meshes))
   ;; the muscles are the only geometry here that is not schematic: their
   ;; endpoints are where suji says they attach, and their direction is the line
   ;; whose moment arm it computed
   (map (fn [{:keys [color transform radius-m]} buffers]
          (let [[sx sy sz] (:scale transform)]
            {:buffers buffers :color color
             :transform (assoc transform :scale [(* sx radius-m) sy (* sz radius-m)])}))
        muscles (:muscles meshes))
   (map (fn [{:keys [color transform radius-m]} buffers]
          (let [[sx sy sz] (:scale transform)]
            {:buffers buffers :color color
             :transform (assoc transform :scale [(* sx radius-m) sy (* sz radius-m)])}))
        discs (:discs meshes))))

(defn render!
  "Draw one frame for a scene. A no-op before init completes, so the app can call
  it from the same place whether or not the GPU is ready yet."
  [scene]
  (let [{:keys [viewport meshes]} @state]
    (when (and viewport meshes scene)
      (let [canvas (.getElementById js/document "suji-canvas")
            vp (if canvas (mesh/sync-canvas-size! viewport canvas) viewport)
            ;; re-frame against the canvas' real aspect: `scene/camera` defaults
            ;; to 4:3, and the stage is fluid.
            {:keys [eye target]} (scene/camera (:pose scene)
                                               (/ (double (:width vp)) (max 1 (:height vp))))]
        (when-not (identical? vp viewport) (swap! state assoc :viewport vp))
        (mesh/render-scene! vp (draws-for scene meshes) eye target)))))

(defn init!
  "Bind the canvas and upload the primitives. Calls `on-ready` with the backend
  keyword (`:webgpu` or `:webgl2`) so the UI can state which one it got — an app
  that silently fell back to WebGL 2.0 looks exactly like one that did not."
  [canvas on-ready on-error]
  (-> (mesh/init-canvas! canvas)
      (.then (fn [viewport]
               (let [meshes (upload! viewport)
                     backend (or (:backend viewport) :webgl2)]
                 (reset! state {:viewport viewport :meshes meshes :backend backend})
                 (on-ready backend))))
      (.catch (fn [e]
                ;; Neither backend available. Say so; do not fall back to a 2-D
                ;; drawing that looks like the real viewport (the repo-wide 3D
                ;; mandate forbids a DOM/Canvas2D stand-in for the viewport, and
                ;; a silent one would be worse than an honest empty frame).
                (on-error (or (.-message e) (str e)))))))
