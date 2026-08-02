(ns lt.objs.trace
  "What actually fired, when a chain went quiet.

  Light Table's characteristic failure is silence. A trigger is raised, some
  behaviors run, one of them decides not to act, and what you see is nothing —
  no error, no message, no console line. Every step is *allowed* to decline
  quietly, which is what makes the architecture extensible and what makes a
  break invisible. \"Toggle docs isn't working\" was reported five times with
  five different causes and one symptom.

  Reading the code answers it. So does this, in a second, from outside the
  window.

  Off by default: `lt.object/trace-with!` is nil until something turns it on,
  so the cost of having this is one atom deref per raise. On, it keeps a bounded
  ring of three kinds of record:

  | kind | means |
  |---|---|
  | `:raised` | a trigger went out, and how many behaviors were listening |
  | `:ran` | one of them ran, and for how long |
  | `:threw` | one of them threw, which `lt.object` catches |

  `:raised` with `:listeners 0` is the one that is hardest to see any other
  way: nothing is wrong, nothing is reported, and nothing happens."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.notifos :as notifos]))

(def ^:private limit
  "Bounded for the reason the error ring is: an editor left tracing overnight
  should not accumulate a megabyte of records nobody will read."
  2000)

(defonce records (atom []))

(defn- remember! [record]
  (swap! records (fn [rs] (conj (if (< (count rs) limit) rs (subvec rs 1)) record))))

(defn on!
  "Start recording. Clears what was there, because a trace you did not start is
  a trace of something else."
  []
  (reset! records [])
  (object/trace-with! remember!))

(defn off! []
  (object/trace-with! nil)
  nil)

(defn recent
  "The last `n` records, oldest first, optionally only those about `trigger`.

  A trigger filter keeps the behaviors that ran *because of* it: `:ran`
  carries the trigger it was reacting to, so `(recent 50 :editor.doc)` is the
  whole of what one keypress did."
  ([n] (recent n nil))
  ([n trigger]
   (let [rs (if trigger
              (filterv #(= trigger (:trigger %)) @records)
              @records)]
     (vec (take-last n rs)))))

(defn- ->line [{:keys [kind trigger behavior listeners ms error tags]}]
  (case kind
    :raised (str "  " trigger "  → " listeners
                 (if (= 1 listeners) " listener" " listeners")
                 (when (zero? (or listeners 0)) "   ← nothing is listening")
                 (when (seq tags) (str "   " (pr-str (vec (sort tags))))))
    :ran (str "      ran " (if (coll? behavior) (first behavior) behavior)
              (when (and ms (> ms 1)) (str "  " ms "ms")))
    :threw (str "      THREW " (if (coll? behavior) (first behavior) behavior)
                "  " error)
    (pr-str kind)))

(defn report
  "The trace as text, for a person or for a terminal."
  ([] (report 200 nil))
  ([n trigger]
   (let [rs (recent n trigger)]
     (if (empty? rs)
       (str "nothing traced" (when trigger (str " for " trigger))
            ". Run 'Trace: Start' first.")
       (string/join "\n" (map ->line rs))))))

(cmd/command {:command :trace.start
              :desc "Trace: Start recording what fires"
              :doc "Records every raise and every behavior invocation until you
                    stop. Off by default, so nothing here costs anything until
                    you ask for it."
              :exec (fn []
                      (on!)
                      (notifos/set-msg! "Tracing. Do the thing, then run 'Trace: Show'."))})

(cmd/command {:command :trace.stop
              :desc "Trace: Stop recording"
              :exec (fn []
                      (off!)
                      (notifos/set-msg! (str "Stopped. " (count @records) " records kept.")))})

(cmd/command {:command :trace.show
              :desc "Trace: Show what fired"
              :exec (fn []
                      (js/lt.objs.console.log (report))
                      (notifos/set-msg! (str (count @records) " records — see the console.")))})
