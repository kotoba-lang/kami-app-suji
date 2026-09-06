(ns kami.app-suji.viewport
  "The 3-D viewport: the only namespace in this app that touches a GPU.

  It owns no geometry and no camera maths. `kami.webgpu.geometry` tessellates the
  primitives, `kami.app-suji.scene` places them, and `kami.webgpu.mesh` executes —
  including the WebGPU→WebGL 2.0 fallback, which `init-canvas!` performs internally,
  so this app never inspects `navigator.gpu` itself.

  Meshes are uploaded ONCE and re-drawn per frame with a different transform each.
  Re-uploading per frame would make every slider move allocate GPU buffers for
  geometry that never changed — and stature IS a slider, so the bones have to
  track a changing length without being re-tessellated. They do, because every
  template is generated at unit length and unit shaft radius and the transform
  carries the real size; see the note in `kami.app-suji.scene`."
  (:require [kami.app-suji.scene :as scene]
            [kami.webgpu.geometry :as geom]
            [kami.webgpu.mesh :as mesh]))

(defonce state (atom {:viewport nil :meshes nil :backend nil}))

(def ^:private unit-cylinder
  "Radius 1, height 1, along +Y and centred. Still the right shape for a line of
  action: a muscle in this model IS a line and has no cross-section, so giving it
  anatomical bulk would be inventing a claim the physics does not make."
  (geom/cylinder 1.0 1.0 20))

(def ^:private joint-sphere (geom/sphere 1.0 10 16))

(defn- bone-template
  "The mesh for one bone, at ITS OWN length and girth — see the long note in
  `kami.app-suji.scene` for why it is not a unit mesh scaled.

  The app owns the catalogue of proportions; `kami.webgpu.geometry` owns every
  number derived from them. This function is the whole of the app's contact with
  tessellation, and it is a two-way dispatch with no arithmetic in it."
  [{:keys [kind length-m radius-m]}]
  (let [{:keys [generator params]} (get scene/bone-shapes kind
                                        (get scene/bone-shapes scene/default-bone-shape))]
    (case generator
      :vertebral-body (geom/vertebral-body (assoc params :length length-m :radius radius-m))
      (geom/long-bone (assoc params :length length-m :shaft-radius radius-m)))))

;; ONE UPLOAD PER DRAWN OBJECT, not one per shape.
;;
;; `mesh/render-scene!` reads its per-draw uniforms — the MVP and the colour —
;; out of the buffer handle that came back from `upload-mesh!`, writing them with
;; `write-buffer!` as it encodes each draw. Every draw in the pass is encoded
;; before any of them executes, so N draws that share one uploaded mesh all read
;; the LAST uniform written: they land on top of each other and the frame shows
;; one object. Measured 2026-09-06 — the six bones rendered as a single rod, with
;; no error and a plausible-looking picture, which is the worst way for this to
;; fail. The buffers are per-slot and re-transformed each frame; the sphere and
;; the cylinder are tessellated once each at load, and each bone shape once the
;; first time a segment asks for it.
(def ^:private bone-slots 12)
(def ^:private joint-slots 12)
(def ^:private muscle-slots 20)
(def ^:private disc-slots 12)

(defn- upload! [viewport]
  (let [ctx (:mesh-context viewport)]
    {:joints (vec (repeatedly joint-slots #(mesh/upload-mesh! ctx joint-sphere)))
     :muscles (vec (repeatedly muscle-slots #(mesh/upload-mesh! ctx unit-cylinder)))
     :discs (vec (repeatedly disc-slots #(mesh/upload-mesh! ctx unit-cylinder)))}))

;; Bone buffers are uploaded LAZILY, keyed by [slot shape], for two reasons that
;; pull in opposite directions.
;;
;; They cannot be uploaded once per shape: the per-draw uniform note above means
;; one buffer per DRAWN OBJECT, so slot 3 and slot 4 need their own even when they
;; are the same shape. And they cannot all be uploaded up front: there are nine
;; shapes and twelve slots, so eagerly covering the product is 108 uploads of
;; which at most twelve are ever drawn.
;;
;; Lazily is also what makes a segment this app has never heard of safe. suji is
;; growing a lower limb and a scapulothoracic element in other hands right now;
;; a name that is not in the catalogue resolves to `default-bone-shape` and gets
;; uploaded the first frame it appears, rather than needing this file edited.
(defn- bone-buffers! [ctx slot spec]
  (let [k [slot spec]]
    (or (get-in @state [:bones k])
        (let [b (mesh/upload-mesh! ctx (bone-template spec))]
          (swap! state assoc-in [:bones k] b)
          b))))

(defn- handle-counts
  "What was actually uploaded, read back off the handle `upload-mesh!` returned.
  The two backends name these differently — WebGPU gives :vertex-count/:idx-count,
  WebGL 2.0 gives :index-count and keeps the source geometry — so this reads
  whichever is there rather than trusting the mesh the app THINKS it uploaded."
  [h]
  {:vertices (or (:vertex-count h) (count (:positions (:geometry h))))
   :indices (or (:idx-count h) (:index-count h))})

(defn- draws-for
  "scene → the draw list `render-scene!` consumes. The unit cylinder has radius 1,
  so a bone's drawn thickness is a scale on X and Z; its length is the scale on Y,
  which `scene` already put in the transform.

  A draw with no slot is DROPPED and reported rather than silently overlapping an
  earlier one — see the note above. If this ever fires, raise the slot counts."
  [ctx {:keys [bones joints muscles discs]} meshes]
  (doseq [[label n cap] [["bones" (count bones) bone-slots]
                         ["joints" (count joints) joint-slots]
                         ["muscles" (count muscles) muscle-slots]
                         ["discs" (count discs) disc-slots]]]
    (when (> n cap)
      (js/console.error "kami-app-suji:" n label "but only" cap "GPU slots — raise the slot count")))
  (concat
   ;; the mesh already carries the girth and (to within the length bucket) the
   ;; length, so all that is left on the transform is the leftover bucket ratio
   (map-indexed (fn [i {:keys [color transform mesh]}]
                  (let [[_ sy _] (:scale transform)]
                    {:buffers (bone-buffers! ctx i mesh)
                     :color color
                     :transform (assoc transform :scale [1.0 (/ sy (:length-m mesh)) 1.0])}))
                (take bone-slots bones))
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

(defn- report-geometry!
  "Publish what the GPU was actually given for the bones, on `window`.

  This exists for `scripts/verify-browser.cljs`, and it is deliberately read off
  the buffer handles rather than off the scene: the check has to be able to fail
  when the upload path regresses, not merely when the scene map changes. The
  counts come back from `upload-mesh!`; the verifier recomputes the same numbers
  from `kami.webgpu.geometry` under nbb and compares, so a bone drawn with the
  wrong mesh is a mismatch between two runtimes rather than a claim this app
  makes about itself.

  `slots` is the set of distinct [slot shape] buffer keys in the frame: if it is
  ever smaller than the bone count, two bones are sharing one uniform buffer and
  the frame is showing fewer bones than it thinks (see the note above)."
  [ctx {:keys [bones]}]
  (let [drawn (map-indexed (fn [i {:keys [label geo mesh]}]
                             (let [{:keys [vertices indices]} (handle-counts (bone-buffers! ctx i mesh))]
                               {:label label :shape (name geo)
                                :vertices vertices :indices indices
                                :meshLengthM (:length-m mesh) :meshRadiusM (:radius-m mesh)
                                :slot (str i "/" (name geo))}))
                           (take bone-slots bones))]
    (set! (.-__sujiGeometry js/window)
          (clj->js {:bones (vec drawn)
                    :distinctSlots (count (distinct (map :slot drawn)))
                    ;; the shape that was there before, for the check that says
                    ;; "not that one" without having to hard-code 84
                    :cylinderVertices (count (:positions unit-cylinder))
                    :cylinderIndices (count (:indices unit-cylinder))}))))

(defn render!
  "Draw one frame for a scene. A no-op before init completes, so the app can call
  it from the same place whether or not the GPU is ready yet."
  [scene]
  (let [{:keys [viewport meshes]} @state]
    (when (and viewport meshes scene)
      (let [ctx (:mesh-context viewport)
            canvas (.getElementById js/document "suji-canvas")
            vp (if canvas (mesh/sync-canvas-size! viewport canvas) viewport)
            ;; re-frame against the canvas' real aspect: `scene/camera` defaults
            ;; to 4:3, and the stage is fluid.
            {:keys [eye target]} (scene/camera (:pose scene)
                                               (/ (double (:width vp)) (max 1 (:height vp))))]
        (when-not (identical? vp viewport) (swap! state assoc :viewport vp))
        (mesh/render-scene! vp (draws-for ctx scene meshes) eye target)
        (report-geometry! ctx scene)))))

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
