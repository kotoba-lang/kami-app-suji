(ns verify-browser
  "Drive the built app in a real browser and assert what neither the compiler nor
  the JVM tests can see.

  Four claims, each of which is invisible in the source:

  1. **A GPU backend was actually obtained.** `init-canvas!` falls back from
     WebGPU to WebGL 2.0 silently, and an app that fell back looks exactly like
     one that did not. The page states the backend it got, and this reads it.
  2. **Pixels were drawn.** A viewport that initialises and renders nothing is a
     correct-looking failure — every object present, every promise resolved, and
     a black rectangle. This samples the canvas and requires that it is not one
     uniform colour.
  3. **The physics runs in the browser.** Moving a slider must change the
     readout. Until 2026-09-06 `suji` could not load under ClojureScript at all,
     so this assertion could not have passed at any earlier commit.
  4. **Crossing a view does not load a document.** This is the single-page claim
     and it is unobservable from the code — a nav reads the same whether it
     routes or navigates. A value is left on `window`, the view is crossed, and
     the value has to still be there.

  Run (after a release build and gen-page, with public/ served):
    SUJI_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            ["playwright$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def url (or (.. process -env -SUJI_URL) "http://localhost:8741/"))
(def root-url (if (str/ends-with? url "/") url (str url "/")))
(def shot-path (or (.. process -env -SUJI_SHOT) "/tmp/kami-app-suji.png"))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(def launch-opts
  (let [o #js {:headless true
               ;; software rasterisation still yields a WebGL2 context in headless
               ;; Chromium; without these the canvas can come back uninitialised on
               ;; a machine with no display.
               :args #js ["--use-gl=swiftshader" "--enable-unsafe-swiftshader"
                          "--enable-features=Vulkan"]}
        channel (str (or (.. process -env -PW_CHANNEL) ""))]
    (when (seq channel) (aset o "channel" channel))
    o))

(defn- text-of [page sel]
  (p/let [el (.$ page sel)]
    (if el (.textContent el) "")))

(defn- run-all [thunks]
  (reduce (fn [acc t] (p/then acc (fn [_] (t)))) (p/resolved nil) thunks))

(defn- checks [page]
  [;; 1. mounted
   (fn []
     (p/let [t (text-of page "h1")]
       (check! "the app mounted and rendered its heading" (str/includes? (or t "") "suji") t)))

   ;; 2. a GPU backend was obtained, and the page says which
   (fn []
     (p/let [_ (.waitForTimeout page 1200)
             b (text-of page ".suji-backend")]
       (check! "a GPU backend was obtained (webgpu or webgl2)"
               (contains? #{"webgpu" "webgl2"} (str/trim (or b "")))
               (str "backend reported: " (pr-str b)))))

   ;; 3. the canvas actually has pixels — not one uniform colour
   (fn []
     (p/let [uniq (.evaluate page
                   "(() => {
                      const c = document.getElementById('suji-canvas');
                      if (!c) return -1;
                      const o = document.createElement('canvas');
                      o.width = c.width; o.height = c.height;
                      const g = o.getContext('2d');
                      g.drawImage(c, 0, 0);
                      const d = g.getImageData(0, 0, o.width, o.height).data;
                      const s = new Set();
                      for (let i = 0; i < d.length; i += 4 * 97)
                        s.add(d[i] + ',' + d[i+1] + ',' + d[i+2]);
                      return s.size;
                    })()")]
       (check! "the viewport drew more than one colour (geometry is on screen)"
               (and (number? uniq) (> uniq 3))
               (str "distinct sampled colours: " uniq))))

   ;; 4. the physics runs in the browser: a slider changes the answer
   (fn []
     (p/let [before (text-of page ".suji-figure")
             _ (.evaluate page
                "(() => {
                   const el = document.getElementById('posture-head-flexion-deg');
                   el.value = 55;
                   el.dispatchEvent(new Event('input', {bubbles: true}));
                 })()")
             _ (.waitForTimeout page 300)
             after (text-of page ".suji-figure")]
       (check! "moving the head-flexion slider changes the computed load"
               (and (seq (str/trim (or before ""))) (not= before after))
               (str (pr-str before) " -> " (pr-str after)))))

   ;; 5. the model's refusal reaches the page as a refusal
   (fn []
     (p/let [_ (.evaluate page
                "(() => {
                   const set = (id, v) => { const el = document.getElementById(id);
                     el.value = v; el.dispatchEvent(new Event('input', {bubbles: true})); };
                   set('posture-trunk-flexion-deg', 60);
                   set('posture-trunk-lateral-bend-deg', 40);
                 })()")
             _ (.waitForTimeout page 400)
             body (.evaluate page "document.body.innerText")]
       (check! "a posture outside the model is shown as refused, not as a number"
               (str/includes? (or body "") "適用範囲外")
               "expected the out-of-range marker in the muscle table")
       (check! "and the reason is given, not just a dash"
               (str/includes? (or body "") "力を計算していない")
               "expected the refusal to state why")))

   ;; 6. an out-of-plane control actually moves the picture
   (fn []
     (p/let [before (.evaluate page "document.querySelector('.suji-figure').innerText")
             _ (.evaluate page
                "(() => { const el = document.getElementById('posture-trunk-lateral-bend-deg');
                          el.value = 30; el.dispatchEvent(new Event('input', {bubbles: true})); })()")
             _ (.waitForTimeout page 400)
             uniq (.evaluate page
                   "(() => { const c = document.getElementById('suji-canvas');
                      const o = document.createElement('canvas'); o.width=c.width; o.height=c.height;
                      const g = o.getContext('2d'); g.drawImage(c,0,0);
                      const d = g.getImageData(0,0,o.width,o.height).data; const s = new Set();
                      for (let i=0;i<d.length;i+=4*97) s.add(d[i]+','+d[i+1]+','+d[i+2]);
                      return s.size; })()")]
       (check! "the frontal-plane control still leaves a drawn body on screen"
               (and (number? uniq) (> uniq 3)) (str "distinct colours: " uniq))))

   ;; 7. the frontal load is reported as a number nobody is carrying
   (fn []
     (p/let [body (.evaluate page "document.body.innerText")]
       (check! "the frontal-plane moment is stated as a figure"
               (str/includes? (or body "") "前額面のモーメント")
               "expected the frontal-plane figures")
       (check! "and the page names the muscles that carry it"
               (str/includes? (or body "") "腰方形筋")
               "expected the frontal-plane muscles to be named")))

   ;; 8. the spine view exists and carries the level table with its caveat
   (fn []
     (p/let [_ (.click page "a[href='#/spine']")
             _ (.waitForSelector page "table")
             body (.evaluate page "document.body.innerText")]
       (check! "the spine view lists intervertebral levels"
               (and (str/includes? (or body "") "L5/S1")
                    (str/includes? (or body "") "C7/T1"))
               "expected the level names")
       (check! "with a stress, not only a force"
               (str/includes? (or body "") "MPa") "expected MPa")
       (check! "and it says the level profile is not the validated one"
               (str/includes? (or body "") "検証されていない")
               "expected the unvalidated caveat next to the table")
       (p/let [_ (.click page "a[href='#/']")
               _ (.waitForSelector page "#suji-canvas")]
         nil)))

   ;; 9. it is one page: crossing a view must not load a document
   (fn []
     (p/let [_ (.evaluate page "window.__sujiSameDocument = 'yes'")
             _ (.click page "a[href='#/compare']")
             ;; wait on something that exists ONLY in this view — the nav link
             ;; carries the same words, so waiting on the text matches before the
             ;; view has rendered
             _ (.waitForFunction page
                "() => !document.getElementById('suji-canvas') && document.querySelector('table')"
                #js {} #js {:timeout 8000})
             ;; innerText needs layout; reading it in the same tick as the commit
             ;; returns the shell without the view
             _ (.waitForTimeout page 400)
             marker (.evaluate page "window.__sujiSameDocument || 'LOST'")
             body (.evaluate page "document.body.innerText")]
       (check! "crossing to the comparison view did not load a document"
               (= "yes" marker) (str "window marker after crossing: " (pr-str marker)))
       (check! "the comparison view rendered its own content"
               (and (str/includes? (or body "") "頭部前屈")
                    (str/includes? (or body "") "×頭部重量"))
               "expected the comparison table's own columns")))

   ;; 8. and back, with the canvas alive again
   (fn []
     (p/let [_ (.click page "a[href='#/']")
             _ (.waitForSelector page "#suji-canvas")
             _ (.waitForTimeout page 600)
             marker (.evaluate page "window.__sujiSameDocument || 'LOST'")]
       (check! "returning to the simulator stayed in the same document"
               (= "yes" marker) (str "marker: " (pr-str marker)))))

   (fn [] (p/let [_ (.screenshot page #js {:path shot-path :fullPage true})]
            (check! "screenshot written" (fs/existsSync shot-path) shot-path)))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) launch-opts)
          page (.newPage browser #js {:viewport #js {:width 1280 :height 900}})
          _ (.on page "pageerror" (fn [e] (println "  page error:" (str e))))
          _ (.goto page root-url #js {:waitUntil "networkidle"})
          _ (run-all (checks page))
          _ (.close browser)]
    (let [fails (remove :ok? @results)]
      (println)
      (println (str (count @results) " checks, " (count fails) " failed"))
      ;; An evidence floor: a run that asserted almost nothing must not report a
      ;; pass. Cf. the workspace rule that a check which could not run has to be
      ;; distinguishable from a check that passed.
      (cond
        (< (count @results) 16)
        (do (println "REFUSING to report a pass: only" (count @results) "checks ran.")
            (process/exit 2))
        (seq fails) (process/exit 1)
        :else (process/exit 0)))))

(-main)
