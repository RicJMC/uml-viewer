(ns uml-viewer.adapters.palette
  "Theme colors and the red-green grade ramps shared by the desktop and
  web renderers."
  (:require [uml-viewer.domain.config :as config]))

(def bg [22 28 32])
(def panel [26 36 40])
(def ink [236 236 228])
(def white [255 255 255])
(def muted [157 184 168])
(def gold [232 196 72])
(def line [42 61 54])
(def good [95 181 138])
(def violation [196 42 36])
(def violation-hot [255 64 48])
(def dim-edge [66 86 78])

(defn- mix [a b t]
  (int (+ a (* t (- b a)) 0.5)))

(defn- mix-rgb [c1 c2 t]
  [(mix (nth c1 0) (nth c2 0) t)
   (mix (nth c1 1) (nth c2 1) t)
   (mix (nth c1 2) (nth c2 2) t)])

(defn- grade-ramp [g none red mid green]
  (cond
    (nil? g) none
    (<= g 5.0) (mix-rgb red mid (/ (double g) 5.0))
    :else (mix-rgb mid green (min 1.0 (/ (- (double g) 5.0) 5.0)))))

(defn fill-for
  "RGB fill for a 0–10 red–green grade."
  [grade]
  (grade-ramp grade [36 52 48] [74 40 24] [61 58 24] [30 74 56]))

(defn stroke-for
  "RGB stroke for a 0–10 red–green grade."
  [grade]
  (grade-ramp grade [90 110 100] [224 122 74] [212 192 90] [95 181 138]))

(defn crap-grade-of [m]
  (let [risk (config/crap-risk (:crap m))]
    (when (or risk (nil? (:metrics-status m)))
      (config/crap-grade risk))))

(defn mutation-grade-of [m]
  (let [ratio (config/mutation-ratio m)]
    (when (and (not= "partial" (:mutation-status m))
               (or ratio (nil? (:metrics-status m))))
      (config/mutation-grade ratio))))

(defn grade-of
  "Average of CRAP and mutation 1–10 grades on `m`."
  [m]
  (config/combined-grade (crap-grade-of m) (mutation-grade-of m)))

(defn coverage-ink [p]
  (cond
    (nil? p) muted
    (>= p 0.8) [95 181 138]
    (>= p 0.5) gold
    :else [224 122 74]))

(defn css
  "CSS rgb() for `c`."
  [[r g b]]
  (str "rgb(" (int r) "," (int g) "," (int b) ")"))
