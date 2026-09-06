(ns kami.app-suji.core
  "kami-app-suji — the views, as pure functions of state.

  Every function here takes a state map and returns hiccup. No atom, no re-frame,
  no `js/`: the mount lives in `ui.cljs` and this namespace can be rendered and
  asserted on the JVM, which is how the views are tested without a browser.

  NON-DIAGNOSTIC (G1, 医師法 §17). Every figure shown is mechanical — a moment in
  N·m, a force in N, a fraction of maximum voluntary contraction, a compressive
  load in N or kgf. There is no field in this app that can hold a diagnosis, a
  condition or a recommendation to treat, and adding one would be a charter
  violation in `suji` before it was a UI change here. SELF-REFERENCED (G3): the
  comparison is one body across setups, never one person against another."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [kami.app-suji.route :as route]
            [kami.app-suji.scene :as scene]
            [suji.methods.math :as math]
            [suji.methods.posture :as posture]
            [suji.methods.segment :as segment]
            [suji.methods.strain :as strain]))

;; --- state -------------------------------------------------------------------

(def controls
  "Every slider, as data — label, path, range and unit. The control panel is
  generated from this, so a control cannot be added to the state and forgotten in
  the UI, and the ranges live next to the thing they bound."
  [{:path [:body :total-mass-kg] :label "体重" :unit "kg" :min 35 :max 130 :step 1}
   {:path [:body :stature-m] :label "身長" :unit "m" :min 1.35 :max 2.05 :step 0.01}
   {:path [:posture :head-flexion-deg] :label "頭部前屈" :unit "°" :min 0 :max 60 :step 1}
   {:path [:posture :trunk-flexion-deg] :label "体幹前傾" :unit "°" :min 0 :max 60 :step 1}
   {:path [:posture :shoulder-flexion-deg] :label "肩屈曲" :unit "°" :min 0 :max 90 :step 1}
   {:path [:posture :elbow-flexion-deg] :label "肘屈曲" :unit "°" :min 0 :max 140 :step 1}
   {:path [:session-minutes] :label "連続作業時間" :unit "分" :min 10 :max 480 :step 10}])

(def initial-state
  (merge {:view :simulate
          :body {:total-mass-kg 70.0 :stature-m 1.70}
          :session-minutes 120.0
          :backend nil}
         {:posture (assoc (posture/posture-from-workstation posture/laptop-on-lap)
                          :preset "laptop-on-lap")}))

(defn body-of [state]
  (segment/build-body (get-in state [:body :total-mass-kg])
                      (get-in state [:body :stature-m])))

(defn solved
  "The whole answer for the current state: loads, muscle tensions, session strain
  and the render scene. Pure — this is what the browser recomputes on every slider
  move, and what a test can call directly."
  [state]
  (let [body (body-of state)
        {:keys [loads tensions scene]} (scene/solve-and-scene body (:posture state))]
    {:body body
     :loads loads
     :tensions tensions
     :strains (strain/session-strain tensions (:session-minutes state))
     :scene scene}))

;; --- small view helpers ------------------------------------------------------

(defn- rgb-css
  "A GPU colour rendered for a swatch. This is the one place a scene colour
  reaches CSS, and it is generated from the scene's own numbers rather than
  restated as a literal — a legend that can disagree with the picture is worse
  than no legend."
  [[r g b]]
  (str "rgb(" (int (* 255 r)) " " (int (* 255 g)) " " (int (* 255 b)) ")"))

(defn- figure [value unit label]
  [:div {:class "suji-readout-item"}
   [:div {:class "suji-figure"} value [:span {:class "suji-unit"} " " unit]]
   [:div {:class "suji-unit"} label]])

(defn muscle-row [t st]
  (let [pct (:mvc-pct t)
        band (:band (scene/band-for pct))]
    [:tr
     [:td (str/replace (:name t) "_" " ")]
     [:td (math/fmt-fixed (:force-n t) 0) " N"]
     [:td (math/fmt-fixed pct 1) " %MVC"
      [:div {:class "suji-bar"}
       [:i {:style {:width (str (min 100.0 pct) "%")
                    :background (rgb-css (scene/ramp-rgb pct))}}]]]
     [:td band]
     [:td (strain/stiffness-band (:stiffness-index st))]]))

;; --- views -------------------------------------------------------------------

(defn simulate-view [state]
  (let [{:keys [loads tensions strains scene]} (solved state)
        cerv (:cervical loads)]
    [:div {:class "suji-main"}
     [:div
      [:div {:class "suji-stage"}
       [:canvas {:id "suji-canvas" :aria-label "姿勢の 3D 表示"}]
       [:div {:class "suji-backend"} (or (some-> (:backend state) name) "…")]]
      [:p {:class "suji-note"}
       "色は各分節がぶら下がる関節の %MVC（最大随意収縮に対する割合）。"
       "力学量であって所見ではない。"]
      (into [:div {:class "dds-ext-row"}]
            (for [{:keys [band rgb max-mvc-pct]} (:legend scene)]
              [:span {:class "suji-note"}
               [:span {:class "suji-swatch" :style {:background (rgb-css rgb)}}]
               band
               (when (math/finite? max-mvc-pct)
                 (str " <" (math/fmt-fixed max-mvc-pct 0) "%"))]))]
     [:div {:class "suji-readout"}
      (dds/card
       (dds/heading 3 "頸椎にかかる圧縮荷重")
       [:div {:class "dds-ext-row"}
        (figure (math/fmt-fixed (:compressive-load-kgf cerv) 1) "kgf" "圧縮荷重")
        (figure (math/fmt-fixed (:multiplier-vs-head cerv) 1) "×" "頭部重量比")
        (figure (math/fmt-fixed (:compressive-load-n cerv) 0) "N" "同 (N)")])
      (dds/card
       (dds/heading 3 "関節モーメント")
       (dds/table
        {:headers ["関節" "モーメント" "内訳"]
         :rows (mapv (fn [j] [(:joint j) (str (math/fmt-fixed (:moment-nm j) 2) " N·m") (:note j)])
                     (:joints loads))}))
      (dds/card
       (dds/heading 3 "筋の緊張と強張り")
       (dds/table
        {:headers ["筋" "張力" "%MVC" "帯" (str (int (:session-minutes state)) "分後")]
         :rows (mapv (fn [t st] [(str/replace (:name t) "_" " ")
                                 (str (math/fmt-fixed (:force-n t) 0) " N")
                                 (str (math/fmt-fixed (:mvc-pct t) 1) " %")
                                 (:band (scene/band-for (:mvc-pct t)))
                                 (strain/stiffness-band (:stiffness-index st))])
                     tensions strains)}))]]))

(defn compare-view [state]
  (let [body (body-of state)]
    [:div {:class "dds-ext-container"}
     (dds/section {}
      (dds/heading 2 "作業環境の比較")
      [:p {:class "suji-note"}
       "同じ身体（" (math/fmt-fixed (get-in state [:body :total-mass-kg]) 0) " kg / "
       (math/fmt-fixed (get-in state [:body :stature-m]) 2) " m）を 3 つの設定に置いた結果。"
       "同一人物の設定間比較であり、人と人を並べるものではない（G3）。"]
      (dds/table
       {:headers ["作業環境" "頭部前屈" "頸椎圧縮" "×頭部重量" "L5/S1" "最大 %MVC"]
        :rows (mapv (fn [w]
                      (let [p (posture/posture-from-workstation w)
                            {:keys [loads tensions]} (scene/solve-and-scene body p)
                            cerv (:cervical loads)
                            ls (first (filter #(= "lumbosacral" (:joint %)) (:joints loads)))]
                        [(:name w)
                         (str (math/fmt-fixed (:head-flexion-deg p) 0) "°")
                         (str (math/fmt-fixed (:compressive-load-kgf cerv) 1) " kgf")
                         (str (math/fmt-fixed (:multiplier-vs-head cerv) 1) "×")
                         (str (math/fmt-fixed (:moment-nm ls) 1) " N·m")
                         (str (math/fmt-fixed (apply max (map :mvc-pct tensions)) 1) " %")]))
                    posture/reference-workstations)}))]))

(defn method-view [_state]
  [:div {:class "dds-ext-container"}
   (dds/section {}
    (dds/heading 2 "計算の中身")
    [:p "姿勢（関節角）→ 前方運動学で 3 次元に配置 → 静的逆動力学（RNEA の重力項）→ "
        "関節モーメント → Hill 型モーメントアームで筋張力と %MVC → "
        "Rohmert の等尺性持久モデルで作業時間ぶんの強張り指数。"]
    (dds/heading 3 "何が検証済みで、何がそうでないか")
    [:ul
     [:li [:strong "検証済み"] "：頸椎の圧縮荷重は Hansraj (2014) の前方頭位の表を再現する"
          "（0°→1 倍 … 60°→5 倍、10% 以内）。"]
     [:li [:strong "力学的だが例示的"] "：筋の %MVC と Rohmert の強張り指数。"
          "PCSA とモーメントアームは代表値であって個人の測定値ではない（G7）。"]
     [:li [:strong "未実装"] "：矢状面 2 次元の連鎖であり、前額面・回旋・冗長筋の"
          "静的最適化・角度依存モーメントアームは持たない。"]]
    (dds/heading 3 "境界")
    [:p "力学量だけを返す。診断・処方・治療は表現できない（医師法 §17 / G1）。"
        "計測ハードウェアを持たず、入力は姿勢のパラメータである（薬機法 / G2）。"]
    [:p {:class "suji-note"}
     "物理は " [:a {:href "https://github.com/cloud-itonami/suji"} "cloud-itonami/suji"]
     "、描画は " [:a {:href "https://github.com/kotoba-lang/webgpu"} "kotoba-lang/webgpu"]
     "（WebGPU、WebGL 2.0 フォールバック）。"])])

(defn control-panel [state]
  (into [:div {:class "dds-ext-stack"}
         (dds/heading 3 "入力")
         (into [:div {:class "dds-ext-row"}]
               (for [w posture/reference-workstations]
                 (dds/button (:name w)
                             {:type (if (= (:name w) (get-in state [:posture :preset]))
                                      :solid-fill :outline)
                              :size "sm"
                              :attrs {:data-preset (:name w)}})))]
        (for [{:keys [path label unit min max step]} controls]
          [:div {:class "suji-control"}
           [:label {:for (str/join "-" (map name path))}
            label " " [:span {:class "suji-unit"}
                       (math/fmt-fixed (get-in state path) (if (< step 1) 2 0)) " " unit]]
           [:input {:type "range" :id (str/join "-" (map name path))
                    :min min :max max :step step
                    :value (get-in state path)
                    :data-path (str/join "/" (map name path))}]])))

(defn app
  "The whole page body for a state. One shell; the view is a value."
  [state]
  (let [view (:view state)]
    [:div {:class "suji-shell"}
     [:header {:class "dds-ext-row"}
      (dds/heading 1 "筋 suji — 姿勢がつくる負荷")
      (route/nav view)]
     [:main
      (case view
        :simulate [:div (simulate-view state) (dds/container (control-panel state))]
        :compare (compare-view state)
        :method (method-view state)
        (simulate-view state))]]))
