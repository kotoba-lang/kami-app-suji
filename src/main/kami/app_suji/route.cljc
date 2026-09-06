(ns kami.app-suji.route
  "The app's addressable views, and the fragment that addresses them.

  kami-app-suji is a single-page app (the kotoba-lang default). One document, one
  bundle, one mount; moving between the simulator and the comparison changes state,
  not location.

  The fragment, not a path: this is served by a static host, so `pushState` to
  `/compare` gives a URL that works until someone reloads it and then 404s. A
  fragment never reaches the host.

  Views are data and the nav is generated from `views`, so a view cannot exist
  without being reachable — the failure this prevents is a view added to the
  dispatch and forgotten in the nav, which is dead code that looks live.

  NOTE (2026-09-06): this is the THIRD copy of this ~60-line shape in the
  workspace (`kami-app-daw`, `kami-app-nle`, here), which is the extraction
  trigger the `kotoba-uiux` skill names. Extracting it — and migrating the two
  existing apps onto the extraction, without which it is not shared — is its own
  change; it is recorded in the ADR rather than half-done here."
  (:require [jp-go-dds.core :as dds]))

(def views
  [{:id :simulate :fragment "#/" :label "姿勢シミュレーション"}
   {:id :compare :fragment "#/compare" :label "作業環境の比較"}
   {:id :method :fragment "#/method" :label "計算の中身"}])

(def default-view (first views))

(defn fragment->view
  "Resolve a location fragment to a view. Unknown, empty and nil all land on the
  default — an address bar is user input, and a typo must not blank the app."
  [fragment]
  (let [f (or fragment "")]
    (or (first (filter #(= (:fragment %) f) views))
        (first (filter #(and (not= "#/" (:fragment %))
                             (re-find (re-pattern (str "^#/?" (name (:id %)))) f))
                       views))
        default-view)))

(defn nav
  "The view switcher: real anchors that are also design-system controls."
  [active-id]
  (into [:nav {:class "dds-ext-row" :aria-label "Views"}]
        (for [{:keys [id fragment label]} views
              :let [active? (= id active-id)]]
          (dds/button label {:type (if active? :solid-fill :text)
                             :size "sm"
                             :href fragment
                             :attrs (cond-> {} active? (assoc :aria-current "page"))}))))
