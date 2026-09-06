(ns kami.app-suji.repo-test
  "Invariants about the repository itself, not about the physics.

  JVM-only on purpose: it reads tracked files off disk, which is a property of
  the checkout rather than of anything portable.

  WHY THIS EXISTS. On 2026-09-07 a merge in this repo was committed with conflict
  markers still in `scripts/verify-browser.cljs`, and the suite stayed green —
  `clojure -M:test` compiles `src` and `test`, and the browser checker is an nbb
  script on neither path. So the one file whose job is to catch things nothing
  else catches was itself outside every check. The markers were found by reading
  the file, which is not a method."
  (:require [clojure.java.io :as io]
            [kami.app-suji.core :as core]
            [kami.app-suji.core-test]
            [suji.methods.attachment :as attachment]
            [suji.methods.recruit]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- tracked-files []
  (->> (:out (shell/sh "git" "ls-files"))
       str/split-lines
       (remove str/blank?)
       (map io/file)
       (filter #(.isFile ^java.io.File %))))

(deftest no-tracked-file-carries-a-merge-conflict-marker
  (let [files (tracked-files)
        ;; Anchored to the line start: `<<<<<<<` can legitimately appear inside a
        ;; string or a comment, and only a marker sits alone at column zero.
        marker #"(?m)^(<{7}|={7}|>{7})(\s|$)"
        hits (keep (fn [^java.io.File f]
                     (let [s (slurp f)]
                       (when (re-find marker s)
                         (.getPath f))))
                   files)]
    (is (seq files) "git ls-files returned nothing — this test measured no files")
    (is (empty? hits)
        (str "conflict markers are still in: " (pr-str (vec hits))))))

(deftest the-browser-checker-is-at-least-readable
  ;; It is never compiled by this suite, so nothing else would notice if it were
  ;; syntactically broken until someone ran it — which is exactly what happened.
  (let [f (io/file "scripts/verify-browser.cljs")]
    (is (.exists f) "scripts/verify-browser.cljs is missing")
    ;; The JVM reader has no reader function for ClojureScript's tagged literals
    ;; (`#js`), so hand it a passthrough. Without this the test errors on the
    ;; checker's own `#js {}` options maps and reports a reader limitation as if
    ;; the file were broken — a check that fails for the wrong reason.
    ;; The read is caught rather than allowed to propagate, so an unreadable file
    ;; fails with THIS test's label. Letting it throw discriminates too, but the
    ;; run then reports a reader exception rather than the thing being asserted —
    ;; and a check that fails for a reason other than the one it names is the
    ;; defect this repo keeps finding.
    (let [result (try
                   (binding [*default-data-reader-fn* (fn [_tag v] v)]
                     (with-open [r (java.io.PushbackReader. (io/reader f))]
                       {:forms (doall (take-while #(not= ::eof %)
                                                  (repeatedly #(read {:eof ::eof :read-cond :allow} r))))}))
                   (catch Exception e {:error (.getMessage e)}))]
      (is (nil? (:error result))
          (str "the browser checker does not read as Clojure data: " (:error result)))
      (is (< 5 (count (:forms result)))
          (str "expected the checker to read as many forms, got " (count (:forms result)))))))

(deftest the-readme-does-not-call-absent-what-the-model-has
  ;; The README's "いま無いもの（正直に）" section had five items on 2026-09-07 and
  ;; every one of them was false — each named something the model had acquired,
  ;; and none had been corrected on the day it arrived. `method-view` had the same
  ;; section go stale the same way; there the fix was to compute the counts, which
  ;; a static file cannot do. This is the substitute: whatever the README says is
  ;; absent has to actually be absent.
  ;;
  ;; Each probe is a fact about the model, not about the prose, so the test fails
  ;; when the MODEL gains the thing rather than when someone rewords the sentence.
  ;; Only the BULLETS are read, not the whole section. The section also carries a
  ;; table recording what each false claim used to say — quoting a corrected claim
  ;; is not making it, and a test that cannot tell a record from an assertion
  ;; would force the document to drop the record to stay green.
  (let [readme (slurp "README.md")
        section (second (str/split readme #"## いま無いもの（正直に）"))
        gaps (when section
               (->> (str/split-lines section)
                    (take-while #(not (str/starts-with? % "## ")))
                    (filter #(str/starts-with? % "- "))
                    (str/join "\n")))]
    (is (some? section) "the README has no `いま無いもの` section")
    (is (seq gaps) "the section has no bullets, so this asserts nothing")
    (doseq [[phrase present? what]
            [["前額面・回旋を持たない"
              (seq (filter #(= :frontal (:axis %)) (vals attachment/muscles)))
              "frontal-axis muscles"]
             ["Crowninshield–Brand 型）を持たない"
              (some? (resolve 'suji.methods.recruit/share))
              "recruit/share"]
             ["モーメントアームは角度に依存しない定数"
              ;; the same muscle at two head angles, through the app's own solve
              (let [arm (fn [d] (:coeff (first (filter #(= "cervical_extensors" (:name %))
                                                       (:tensions (core/solved
                                                                   (assoc-in core/initial-state
                                                                             [:posture :head-flexion-deg] d)))))))]
                (not= (arm 0.0) (arm 20.0)))
              "a moment arm that changes with posture"]
             ["骨の形状は円柱"
              (not-any? #{:cylinder} (map :geo (:bones (:scene (core/solved core/initial-state)))))
              "bones drawn as anatomical shapes"]]]
      (when present?
        (is (not (str/includes? gaps phrase))
            (str "the README says `" phrase "` and the model has " what))))))

(deftest the-readme-verification-table-is-not-stale
  ;; The table carries its own warning that it had been stale once, and it went
  ;; stale again: 17 tests / 126 assertions and 16 browser checks, against 53 and
  ;; 31. A number written by hand beside a number that grows will be wrong; this
  ;; makes it wrong loudly.
  ;;
  ;; A BOUND, not an equality. An exact match would make this test the thing that
  ;; goes stale — every commit adding a test would have to edit the README too,
  ;; and the first person in a hurry would loosen the assertion rather than the
  ;; document. Half is chosen because the failure that actually happened was a
  ;; factor of three, and a bound that cannot catch the failure it was written for
  ;; is theatre.
  ;;
  ;; ⚠ My first version bounded against the deftests of ONE namespace, which 17
  ;; satisfied — it passed on exactly the stale table it exists to catch.
  (let [readme (slurp "README.md")
        nss '[kami.app-suji.core-test kami.app-suji.scene-test
              kami.app-suji.route-test kami.app-suji.bone-shape-test
              kami.app-suji.repo-test]
        actual (reduce + (for [n nss]
                           (do (require n)
                               (count (filter #(:test (meta %)) (vals (ns-publics n)))))))
        claimed (some-> (re-find #"(\d+) tests / ([\d,]+) assertions" readme)
                        second parse-long)]
    (is (< 20 actual) (str "only " actual " deftests found, so the bound is meaningless"))
    (is (some? claimed) "the verification table states no test count")
    (is (<= (quot actual 2) claimed)
        (str "the table claims " claimed " tests and the suite has " actual
             " — more than a factor of two apart"))))
