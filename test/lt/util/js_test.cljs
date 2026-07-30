(ns lt.util.js-test
  "Tests for throttle and debounce, which replaced a vendored jQuery plugin.

  Both are timing-dependent, so these use real timers with margins wide enough
  that a slow machine does not fail them. Each case checks a property that
  distinguishes the two: a debounced function never runs while calls keep
  arriving, and a throttled one leads on the first call."
  (:require [cljs.test :refer [deftest is async]]
            [lt.util.js :as js-util]))

(deftest debounce-runs-once-after-the-calls-stop
  (async done
    (let [calls (atom 0)
          f (js-util/debounce 30 #(swap! calls inc))]
      (f) (f) (f)
      (is (= 0 @calls) "does not lead")
      (js/setTimeout (fn []
                       (is (= 1 @calls) "runs once, after the last call")
                       (done))
                     120))))

(deftest debounce-restarts-its-delay-on-every-call
  (async done
    (let [calls (atom 0)
          f (js-util/debounce 60 #(swap! calls inc))]
      (f)
      (js/setTimeout f 30)
      (js/setTimeout (fn []
                       ;; 45ms after the first call and 15ms after the second,
                       ;; so a delay that did not restart would have fired.
                       (is (= 0 @calls) "the second call pushed the deadline out")
                       (js/setTimeout (fn []
                                        (is (= 1 @calls))
                                        (done))
                                      120))
                     45))))

(deftest throttle-leads-then-trails
  (async done
    (let [calls (atom 0)
          f (js-util/throttle 40 #(swap! calls inc))]
      (f)
      (is (= 1 @calls) "leads: nothing has run recently, so it runs now")
      (f) (f)
      (is (= 1 @calls) "the calls during the window are collapsed")
      (js/setTimeout (fn []
                       (is (= 2 @calls) "and one of them trails")
                       (done))
                     140))))

(deftest throttle-passes-arguments-through
  (async done
    (let [seen (atom nil)
          f (js-util/throttle 20 #(reset! seen [%1 %2]))]
      (f :a :b)
      (js/setTimeout (fn []
                       (is (= [:a :b] @seen))
                       (done))
                     60))))
