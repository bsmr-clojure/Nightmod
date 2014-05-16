(ns nightmod.screens
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [nightcode.editors :as editors]
            [nightcode.ui :as ui]
            [nightcode.utils :as nc-utils]
            [nightmod.utils :as u]
            [play-clj.core :refer :all]
            [play-clj.ui :refer :all]
            [seesaw.core :as s])
  (:import [java.awt Toolkit]
           [java.awt.datatransfer Clipboard ClipboardOwner StringSelection]))

(declare nightmod main-screen blank-screen overlay-screen)

(def ^:const text-height 40)
(def ^:const pad-space 5)

(defn read-title
  [f]
  [(or (-> (io/file f u/settings-file)
            slurp
            edn/read-string
            :title
            (try (catch Exception _)))
       (-> (.getName f)
           Long/parseLong
           u/format-date
           (try (catch Exception _)))
       (.getName f))
   (.getCanonicalPath f)])

(defn load-project!
  [path]
  (on-gl (set-screen! nightmod blank-screen overlay-screen))
  ; save in project-dir so the reset button works
  (reset! u/project-dir path)
  ; save in tree-projects so the up button is hidden correctly
  (swap! ui/tree-projects conj path)
  ; save in tree-selection so the file grid is displayed
  (s/invoke-now
    (reset! ui/tree-selection path)))

(defn new-project!
  [template]
  (when-let [project-name (u/new-project-name! template)]
    (some->> (u/new-project-dir! project-name)
             (u/new-project! template project-name)
             load-project!)))

(defn home!
  []
  (s/invoke-now
    (editors/remove-editors! @u/project-dir))
  (u/toggle-glass! false)
  (set-screen! nightmod main-screen))

(defn restart!
  []
  (reset! u/project-dir @u/project-dir))

(defn scrollify
  [widget]
  (scroll-pane widget (style :scroll-pane nil nil nil nil nil)))

(defn error-str
  []
  (->> [(some-> @u/error .toString)
        (when (and @u/error @u/stack-trace?)
          (for [elem (.getStackTrace @u/error)]
            (.toString elem)))]
       flatten
       (remove nil?)
       (string/join \newline)))

(defn set-clipboard!
  [s]
  (let [clip-board (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (.setContents clip-board
      (StringSelection. s)
      (reify ClipboardOwner
        (lostOwnership [this clipboard contents])))))

(defscreen main-screen
  :on-show
  (fn [screen entities]
    (update! screen :renderer (stage) :camera (orthographic))
    (when-not @u/main-dir
      (reset! u/main-dir (u/get-data-dir)))
    (let [ui-skin (skin "uiskin.json")
          create-button (fn [[display-name path]]
                          (text-button display-name ui-skin :set-name path))
          saved-games (->> (io/file @u/main-dir)
                           .listFiles
                           (filter #(.isDirectory %))
                           (sort-by #(.getName %))
                           (map read-title)
                           (map create-button))
          new-games (->> (for [i (range (count u/templates))
                               :let [template (nth u/templates i)]]
                           [(nc-utils/get-string template) template])
                         (map create-button))
          saved-column (-> (label (nc-utils/get-string :load) ui-skin)
                           (cons saved-games)
                           (vertical :pack)
                           scrollify)
          load-column (-> (label (nc-utils/get-string :new) ui-skin)
                          (cons new-games)
                          (vertical :pack)
                          scrollify)]
      (table [(when (seq saved-games)
                [saved-column :pad pad-space pad-space pad-space pad-space])
              [load-column :pad pad-space pad-space pad-space pad-space]]
             :align (align :center)
             :set-fill-parent true)))
  
  :on-render
  (fn [screen entities]
    (clear!)
    (render! screen entities))
  
  :on-resize
  (fn [screen entities]
    (height! screen (:height screen)))
  
  :on-ui-changed
  (fn [screen entities]
    (when-let [n (text-button! (:actor screen) :get-name)]
      (if (contains? (set u/templates) n)
        (new-project! n)
        (load-project! n)))
    nil))

(defscreen blank-screen
  :on-render
  (fn [screen entities]
    (clear!)))

(defscreen overlay-screen
  :on-show
  (fn [screen entities]
    (update! screen :camera (orthographic) :renderer (stage))
    (let [ui-skin (skin "uiskin.json")]
      [(-> (label "" ui-skin
                  :set-wrap true
                  :set-font-scale 0.8
                  :set-alignment (bit-or (align :left) (align :bottom)))
           (scroll-pane (style :scroll-pane nil nil nil nil nil))
           (assoc :id :text :x pad-space :y (+ text-height (* 2 pad-space))))
       (-> [(check-box (nc-utils/get-string :stack-trace) ui-skin
                       :set-name "stack-trace")
            (text-button (nc-utils/get-string :copy) ui-skin
                         :set-name "copy")]
           (horizontal :space (* 2 pad-space) :pack)
           (assoc :id :error-buttons :x pad-space :y pad-space))
       (-> [(text-button (nc-utils/get-string :home) ui-skin
                         :set-name "home")
            (text-button (nc-utils/get-string :restart) ui-skin
                         :set-name "restart")
            (text-button (nc-utils/get-string :files) ui-skin
                         :set-name "files")]
           (horizontal :space (* 2 pad-space) :pack)
           (assoc :id :menu :x pad-space))]))
  
  :on-render
  (fn [screen entities]
    (->> (for [e entities]
           (case (:id e)
             :text (do
                     (label! (scroll-pane! e :get-widget) :set-text (error-str))
                     (assoc e :width (if (.isVisible (u/glass))
                                       (- (game :width) u/editor-width)
                                       (game :width))))
             :error-buttons (do
                              (doseq [b (horizontal! e :get-children)]
                                (actor! b :set-visible (some? @u/error)))
                              e)
             e))
         (render! screen)))
  
  :on-resize
  (fn [{:keys [width height] :as screen} entities]
    (height! screen height)
    (for [e entities]
      (case (:id e)
        :menu (assoc e :y (- height (vertical! e :get-height) pad-space))
        :text (assoc e :width width :height (- height (* 2 (:y e))))
        e)))
  
  :on-ui-changed
  (fn [screen entities]
    (case (text-button! (:actor screen) :get-name)
      "home" (home!)
      "restart" (restart!)
      "files" (u/toggle-glass!)
      "stack-trace" (swap! u/stack-trace? not)
      "copy" (set-clipboard! (error-str))
      nil)
    nil))

(defgame nightmod
  :on-create
  (fn [this]
    (set-screen! this main-screen)))

(add-watch u/error
           :show-error
           (fn [_ _ _ e]
             (when e
               (on-gl (set-screen! nightmod blank-screen overlay-screen)))))
