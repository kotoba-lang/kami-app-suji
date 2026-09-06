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
