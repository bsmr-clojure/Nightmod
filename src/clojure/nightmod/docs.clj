(ns nightmod.docs
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [nightcode.editors :as editors]
            [nightcode.ui :as ui]
            [nightcode.utils :as nc-utils]
            [nightmod.sandbox :as sandbox]
            [seesaw.core :as s])
  (:import [java.awt Desktop]
           [java.net URI]
           [javax.swing.event HyperlinkEvent$EventType TreeSelectionListener]
           [javax.swing.text.html HTMLEditorKit StyleSheet]
           [javax.swing.tree DefaultMutableTreeNode DefaultTreeModel
            TreeSelectionModel]))

(defn blacklisted?
  [{:keys [name]}]
  (contains? sandbox/blacklist-symbols (symbol name)))

(def doc-list (->> (io/resource "docs/index.edn")
                   slurp
                   edn/read-string
                   (remove blacklisted?)))

(def doc-map (into {} (map #(vector (:name %) (:file %)) doc-list)))

(def ns-list (vec (group-by :ns doc-list)))

(defn update-content!
  [node]
  (doto (s/select @ui/root [:#docs-content])
    (.setText (or (some->> node :file (str "docs/") io/resource slurp)
                  ""))
    (.setCaretPosition 0)))

(defn var-node
  [{:keys [ns name] :as node}]
  (proxy [DefaultMutableTreeNode] [node]
    (isLeaf [] true)
    (toString [] name)))

(defn ns-node
  [[ns items]]
  (proxy [DefaultMutableTreeNode] []
    (getChildAt [i] (var-node (nth items i)))
    (getChildCount [] (count items))
    (toString [] ns)))

(defn root-node
  []
  (proxy [DefaultMutableTreeNode] []
    (getChildAt [i] (ns-node (nth ns-list i)))
    (getChildCount [] (count ns-list))))

(defn create-sidebar
  []
  (doto (s/tree :id :docs-sidebar)
    (.setRootVisible false)
    (.setShowsRootHandles true)
    (.setModel (DefaultTreeModel. (root-node)))
    (.addTreeSelectionListener
      (reify TreeSelectionListener
        (valueChanged [this e]
          (update-content! (some-> e
                                   .getPath
                                   .getLastPathComponent
                                   .getUserObject)))))
    (-> .getSelectionModel
        (.setSelectionMode TreeSelectionModel/SINGLE_TREE_SELECTION))))

(defn create-content
  []
  (let [css (doto (StyleSheet.) (.importStyleSheet (io/resource "docs.css")))
        kit (doto (HTMLEditorKit.) (.setStyleSheet css))]
  (doto (s/editor-pane :id :docs-content
                       :editable? false
                       :content-type "text/html")
    (.setEditorKit kit)
    (s/listen :hyperlink (fn [e]
                           (when (and (= (.getEventType e)
                                         HyperlinkEvent$EventType/ACTIVATED)
                                      (Desktop/isDesktopSupported))
                             (.browse (Desktop/getDesktop)
                               (URI. (.getDescription e)))))))))

(defn search!
  [& _])

(def ^:dynamic *widgets* [])

(defn create-widgets
  []
  {:search (doto (s/text :id :search
                         :columns 16
                         :listen [:key-released search!])
             (nc-utils/set-accessible-name! :search)
             (editors/text-prompt! (nc-utils/get-string :search)))})

(defn create-card
  []
  (let [widgets (create-widgets)
        widget-bar (ui/wrap-panel :items (map #(get widgets % %) *widgets*))]
    (s/border-panel :north widget-bar
                    :west (s/scrollable (create-sidebar) :size [200 :by 0])
                    :center (s/scrollable (create-content)))))