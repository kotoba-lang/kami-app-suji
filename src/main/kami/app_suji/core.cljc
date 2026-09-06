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
            [suji.methods.muscle :as muscle]
            [suji.methods.posture :as posture]
            [suji.methods.spine :as spine]
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
   {:path [:posture :wrist-extension-deg] :label "手首伸展" :unit "°" :min 0 :max 45 :step 1}
   {:path [:posture :shoulder-abduction-deg] :label "肩外転（前額面）" :unit "°" :min 0 :max 90 :step 1}
   {:path [:posture :trunk-lateral-bend-deg] :label "体幹側屈（前額面）" :unit "°" :min -40 :max 40 :step 1}
   {:path [:posture :head-rotation-deg] :label "頭部回旋" :unit "°" :min -70 :max 70 :step 1}
   {:path [:session-minutes] :label "連続作業時間" :unit "分" :min 10 :max 480 :step 10}])

(def out-of-plane-defaults
  "`posture/posture-from-workstation` describes a sagittal setup and emits no
  frontal-plane or rotation angles; `pose` treats them as optional and defaults
  them to zero. The app has sliders for them, and a slider needs a value — so the
  defaults are made explicit HERE rather than left to `get-in` returning nil,
  which is how a missing control becomes a NullPointerException at render time
  instead of a zero."
  {:shoulder-abduction-deg 0.0
   :trunk-lateral-bend-deg 0.0
   :head-rotation-deg 0.0})

(defn workstation-posture
  "A reference workstation's posture, with every control this app exposes present."
  [w]
  (merge out-of-plane-defaults
         (posture/posture-from-workstation w)
         {:preset (:name w)}))

(def initial-state
  (merge {:view :simulate
          :body {:total-mass-kg 70.0 :stature-m 1.70}
          :session-minutes 120.0
          :backend nil}
         {:posture (workstation-posture posture/laptop-on-lap)}))

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

(defn coeff-label
  "A task coefficient with its unit. `recruit` returns a moment arm in metres for a
  moment equilibrium and a DIMENSIONLESS direction cosine for a suspended force —
  printing both as millimetres would put a cosine of 0.41 on the page as 410 mm.

  The task is read off the tension entry, which carries it. It used to be looked up
  as `(:task (get suji.methods.attachment/muscles (:name t)))`, and that map is keyed by
  GROUP (`upper_trapezius`) while a tension entry is named by INSTANCE
  (`upper_trapezius/left`) — every suspension muscle is `:paired?`, so the lookup
  returned nil for all of them and the guard was dead. The page shipped six rows of
  `343.3 mm` / `480.9 mm` / `176.9 mm` at the DEFAULT posture: a 34 cm moment arm at
  the shoulder, on the muscles this app's own prose calls the 肩こり muscles. The
  guard was written to prevent exactly that and could not fire once."
  [t]
  (let [c (:coeff t)
        suspension? (= :scapular-suspension (:task t))]
    (cond
      (nil? c) "—"
      suspension? (str (math/fmt-fixed c 2) " (cos)")
      :else (str (math/fmt-fixed (* 1000.0 c) 1) " mm"))))

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
            (concat
             (for [{:keys [band rgb max-mvc-pct]} (:legend scene)]
               [:span {:class "suji-note"}
                [:span {:class "suji-swatch" :style {:background (rgb-css rgb)}}]
                band
                (when (math/finite? max-mvc-pct)
                  (str " <" (math/fmt-fixed max-mvc-pct 0) "%"))])
             ;; the two non-load colours belong in the key too: a reader who sees
             ;; purple with no entry for it has to guess, and both guesses (fine /
             ;; terrible) are claims the model did not make.
             [[:span {:class "suji-note"}
               [:span {:class "suji-swatch" :style {:background (rgb-css scene/ligament-rgb)}}]
               "靭帯が担っている（筋は沈黙）"]
              [:span {:class "suji-note"}
               [:span {:class "suji-swatch" :style {:background (rgb-css scene/refused-rgb)}}]
               "適用範囲外（計算していない）"]
              [:span {:class "suji-note"}
               [:span {:class "suji-swatch" :style {:background (rgb-css scene/unloaded-rgb)}}]
               "この関節を通る筋がモデルに無い"]]))
      (let [f (:frontal loads)
            fl (math/abs* (:lumbosacral-nm f 0.0))
            fs (math/abs* (get-in f [:shoulder-per-side :left] 0.0))]
        (when (or (> fl 0.5) (> fs 0.5))
          [:p {:class "suji-note"}
           "前額面のモーメント —— L5/S1 " [:strong (str (math/fmt-fixed fl 2) " N·m")]
           "、肩（片側）" [:strong (str (math/fmt-fixed fs 2) " N·m")]
           "。腰方形筋・腹斜筋・中部三角筋・広背筋・斜角筋が担う。"
           "これらの筋は 2026-09-06 に足したもので、それ以前この荷重は"
           "「担う筋がモデルに無い」として報告されていた。"]))]
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
       (when-let [ls (seq (filter :ligament? tensions))]
         [:p {:class "suji-note"}
          "靭帯は筋ではない —— 収縮しないので %MVC を持たず、関節が既にそこまで運ばれて"
          "初めて張る。深い体幹前屈では脊柱起立筋が電気的に沈黙し、"
          [:strong "後方靭帯系が荷重を引き受ける"] "（屈曲弛緩）。"
          (when (some :at-limit? ls)
            [:strong "⚠ 一部の靭帯は較正範囲を超えて伸ばされており、表示中の力は"
             "外挿ではなく打ち切った値である。"])])
       [:p {:class "suji-note"}
        "「出せる力」はその筋が" [:strong "この姿勢の長さで"]
        "出せる力（PCSA × 比張力 × Hill の力‑長さ係数）。%MVC の分母はこれであって"
        "ピーク値ではない —— 筋はどの長さでも最大を出せるわけではなく、"
        "長さを決めるのは姿勢だから。⚠ は最大随意収縮を超えていることを示す。"
        "張力の括弧内は（能動 / 受動）—— 受動は伸ばされた組織自身が出す力で、"
        "活動を要さず代謝コストも無い。"]
       (dds/table
        {:headers ["筋" "モーメントアーム" "張力（能動/受動）" "出せる力" "%MVC" "帯"
                   (str (int (:session-minutes state)) "分後")]
         :rows (mapv (fn [t st]
                       (if (:refused t)
                         ;; A refusal is shown AS a refusal. Rendering a dash in
                         ;; the %MVC column and nothing else would let a reader
                         ;; take it for a small number; the reason is the answer.
                         [(str/replace (:name t) "_" " ")
                          (coeff-label t) "—" "—" "適用範囲外" "—" "—"]
                         [(str/replace (:name t) "_" " ")
                          (coeff-label t)
                          ;; active and passive shown apart: one is asked for and
                          ;; costs something, the other is the tissue and does not
                          (str (math/fmt-fixed (:force-n t) 0) " N"
                               (when (and (:passive-n t) (> (:passive-n t) 0.5))
                                 (str " (" (math/fmt-fixed (:active-n t) 0)
                                      " / " (math/fmt-fixed (:passive-n t) 0) ")")))
                          ;; the denominator of the %MVC beside it: what this
                          ;; muscle can produce AT THIS LENGTH, not its peak
                          (str (math/fmt-fixed (:f-max-n t) 0) " N")
                          ;; a LIGAMENT has no %MVC — it cannot contract, so
                          ;; there is no maximum voluntary contraction to be a
                          ;; fraction of. Asking `:refused` here would have missed
                          ;; it; asking whether the number is present does not.
                          (if (muscle/numeric-mvc? t)
                            (str (math/fmt-fixed (:mvc-pct t) 1) " %"
                                 (when (:over-mvc? t) " ⚠"))
                            (if (:ligament? t) "靭帯（収縮しない）" "—"))
                          (or (:band (scene/band-for (:mvc-pct t)))
                              (if (:ligament? t) "靭帯" "—"))
                          (str (strain/stiffness-band (:stiffness-index st))
                               (when (:saturated? st) "（飽和）"))]))
                     tensions strains)})
       ;; The reason comes from the data, not from a sentence written here. There
       ;; is more than one way this model declines — too little leverage for a
       ;; straight line (which a wrapping surface fixes) and a line that would act
       ;; the wrong way at this posture (which nothing here fixes) — and a fixed
       ;; explanation would keep naming the first after the second became the only
       ;; one that happens. Measured 2026-09-06: wrapping surfaces removed every
       ;; leverage-floor refusal, and the hardcoded sentence went on citing them.
       (when-let [r (seq (filter :refused tensions))]
         (into [:div]
               (for [t r]
                 [:p {:class "suji-note"}
                  [:strong (str/replace (:name t) "_" " ")]
                  " の力を計算していない（" (name (:refused t)) "）—— " (:note t)])))
       (when-not (:complete? (muscle/tension-summary tensions loads))
         [:p {:class "suji-note"}
          "この結果は不完全である —— 荷重の一部はどの筋にも割り当てられていない。"]))]]))

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
                         (if-let [mx (:max-mvc-pct (muscle/tension-summary tensions))]
                           (str (math/fmt-fixed mx 1) " %"
                                (when-not (:complete? (muscle/tension-summary tensions loads)) " ⚠"))
                           "適用範囲外")]))
                    posture/reference-workstations)}))]))

(defn spine-view [state]
  (let [{:keys [tensions loads]} (solved state)
        body (body-of state)
        rows (spine/profile body (:posture state) tensions)
        xcheck (spine/cervical-cross-check body (:posture state) tensions (:cervical loads))
        steps (spine/attachment-steps rows)]
    [:div {:class "dds-ext-container"}
     (dds/section {}
      (dds/heading 2 "椎間板レベルごとの圧縮")
      [:p {:class "suji-note"}
       "各レベルに載る重量と、そのレベルを跨ぐ筋張力の軸成分の和を、そのレベルの"
       "椎間板断面積で割ったもの。"
       [:strong "組織の項が支配的である"]
       " —— 伸筋は短いモーメントアームで働くので、小さな外部モーメントを保つのに"
       "大きな力が要り、その力は全部が関節を圧迫する。"
       "その担い手は姿勢で入れ替わるので、筋と靭帯を分けて示す。"]
      (dds/table
       {:headers ["レベル" "部位" "体重ぶん" "筋ぶん" "靭帯ぶん" "合計" "断面積" "圧縮応力"]
        :rows (mapv (fn [r] [(:name r) (name (:region r))
                             (str (math/fmt-fixed (:weight-n r) 0) " N")
                             (str (math/fmt-fixed (:muscle-n r) 0) " N")
                             (str (math/fmt-fixed (:ligament-n r) 0) " N")
                             (str (math/fmt-fixed (:force-n r) 0) " N")
                             (str (math/fmt-fixed (:disc-area-cm2 r) 1) " cm²")
                             (str (math/fmt-fixed (:stress-mpa r) 2) " MPa")])
                    rows)})
      [:p {:class "suji-note"}
       "深い体幹前屈では「筋ぶん」が縮み「靭帯ぶん」が支配的になる（屈曲弛緩）。"
       "そのとき脊椎を圧迫しているのは筋ではなく後方靭帯系であり、"
       [:strong "何を変えれば減るのかが違う"] "。"]
      [:p {:class "suji-note"}
       [:strong "⚠ この表は検証されていない。"]
       "頸椎については、検証済みの集中定数モデル（Hansraj 2014）が "
       (math/fmt-fixed (:lumped-force-n xcheck) 0) " N とするところをこの表は "
       (math/fmt-fixed (:level-force-n xcheck) 0) " N とし、比は "
       (math/fmt-fixed (:ratio xcheck) 2) " 倍ある。"
       "集中定数側は公表値に合わせた実効レバーを使い、この表は筋の幾何モーメントアームを"
       "使うためである。"
       [:strong "検証を継承しているのは集中定数の側だけ"] "である。"]
      (when (seq steps)
        [:p {:class "suji-note"}
         "また、実際の筋は複数の椎骨にまたがって付着するが、このモデルは点で付着させる。"
         "そのため " (str/join "・" (map #(str (:after %) " → " (:at %)) steps))
         " で筋の寄与が段差状に 0 へ落ちる —— これはモデルの人工物であって身体ではない。"]))]))

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
     [:li [:strong "導出値"] "：モーメントアームは筋の起始・停止から幾何で計算する"
          "（定数表ではない）。中立姿勢では従来の定数を 0.2% 以内で再現し、"
          "そこから外れた分だけが幾何由来である。"]
     [:li [:strong "拒否する"] "：直線モデルには巻き付き面が無いので、作用線が関節を"
          "通る近傍では必要張力が発散する。そこではモデルが数値を返さない。"]
     [:li [:strong "未実装"] "：解剖学的メッシュ、腱の巻き付き面、椎間板の個別モデル。"
          "前額面（外転・側屈）と頭部回旋は入力できるが、"
          "それらに対する筋の追加（斜角筋・広背筋など）はまだ無い。"]]
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
        :spine (spine-view state)
        :method (method-view state)
        (simulate-view state))]]))
