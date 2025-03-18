(ns frontend.worker.embedding
  "Fns about text-embedding, add/delete/search items in hnsw"
  (:require [cljs.pprint :as pp]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.missionary :as c.m]
            [frontend.worker-common.util :as worker-util]
            [frontend.worker.state :as worker-state]
            [logseq.common.config :as common-config]
            [logseq.db :as ldb]
            [medley.core :as medley]
            [missionary.core :as m]))

;;; TODOs:
;;; - [x] add :logseq.property/description into text-to-embedding
;;; - [ ] add tags to text-to-embedding
;;; - [x] check webgpu available, transformers.js is slow without webgpu(the difference is ~70 times)
;;; - [ ] expose index-state to ui

(def ^:private empty-vector-search-state
  {:repo->index-info {}              ;; repo->index-info
   :repo->index-building-canceler {} ;; repo->canceler
   :repo->search-canceler {}}) ;; repo->canceler

(def ^:private vector-search-state-keys (set (keys empty-vector-search-state)))

(def ^:private *vector-search-state (atom empty-vector-search-state
                                          :validator
                                          (fn [v] (= vector-search-state-keys (set (keys v))))))

(defn- reset-*vector-search-state!
  [repo & {:keys [index-info index-building-canceler search-canceler]}]
  (reset! *vector-search-state
          (cond-> @*vector-search-state
            index-info              (assoc :repo->index-info {repo index-info})
            index-building-canceler (assoc-in [:repo->index-building-canceler repo] index-building-canceler)
            search-canceler         (assoc-in [:repo->search-canceler repo] search-canceler))))

(defn- cancel-action!
  [repo action]
  (assert (contains? #{:index-building :search} action))
  (let [k {:index-building :repo->index-building-canceler
           :search :repo->search-canceler}]
    (when-let [canceler (get-in @*vector-search-state [k repo])]
      (canceler))
    (swap! *vector-search-state medley/dissoc-in [k repo])))

(defn- stale-block-filter-preds
  "When `reset?`, ignore :logseq.property.embedding/hnsw-label-updated-at in block"
  [reset?]
  (let [preds (cond-> [(fn [b]
                         (let [db-ident (:db/ident b)
                               title (:block/title b)]
                           (and (or (nil? db-ident)
                                    (not (string/starts-with? (namespace db-ident) "logseq.")))
                                (not (string/blank? title))
                                (not (ldb/hidden? title))
                                (nil? (:logseq.property/view-for b))
                                (not (keyword-identical?
                                      :logseq.property/description
                                      (:db/ident (:logseq.property/created-from-property b)))))))]

                (not reset?)
                (conj (fn [b]
                        (let [block-updated-at (:block/updated-at b)
                              hnsw-label-updated-at (:logseq.property.embedding/hnsw-label-updated-at b)]
                          (or (nil? hnsw-label-updated-at)
                              (> block-updated-at hnsw-label-updated-at))))))]
    (apply every-pred preds)))

(defn- stale-block-lazy-seq
  [db reset?]
  (->> (rseq (d/index-range db :block/updated-at nil nil))
       (sequence
        ;; NOTE: assoc :block.temp/search?, so uuid in :block/title will be replaced by content
        (comp (map #(assoc (d/entity db (:e %)) :block.temp/search? true))
              (filter (stale-block-filter-preds reset?))
              (map (fn [b]
                     (assoc b :block.temp/text-to-embedding
                            (if-let [desc (:block/title (:logseq.property/description b))]
                              (str (:block/title b) ": " desc)
                              (:block/title b)))))))))

(defn- partition-by-text-size
  [text-size]
  (let [*current-size (volatile! 0)
        *partition-index (volatile! 0)]
    (partition-by
     (fn [block]
       (let [block-text-size (count (:block.temp/text-to-embedding block))]
         (vswap! *current-size + block-text-size)
         (if (>= text-size @*current-size)
           @*partition-index
           (do (vreset! *current-size block-text-size)
               (vswap! *partition-index inc))))))))

(defn- labels-update-tx-data
  [db e+updated-at-coll added-labels]
  (assert (= (count e+updated-at-coll) (count added-labels)))
  (let [es (map first e+updated-at-coll)
        exist-es (set (keep
                       (fn [b] (when (:block/uuid b) (:db/id b)))
                       (d/pull-many db [:block/uuid :db/id] es)))]
    (mapcat
     (fn [[e updated-at] label]
       (when (contains? exist-es e)
         [[:db/add e :logseq.property.embedding/hnsw-label label]
          [:db/add e :logseq.property.embedding/hnsw-label-updated-at updated-at]]))
     e+updated-at-coll added-labels)))

(defn- task--update-index-info!
  [repo infer-worker]
  (m/sp
    (reset-*vector-search-state! repo :index-info
                                 (js->clj (c.m/<? (.index-info infer-worker repo))
                                          :keywordize-keys true))))
(defn task--embedding-stale-blocks!
  "embedding outdated block-data
  outdate rule: block/updated-at > :logseq.property.embedding/hnsw-label-updated-at"
  [repo conn]
  (m/sp
    (when-let [^js infer-worker @worker-state/*infer-worker]
      (let [stale-blocks (stale-block-lazy-seq @conn false)]
        (doseq [stale-block-chunk (sequence (partition-by-text-size 2000) stale-blocks)]
          (let [e+updated-at-coll (map (juxt :db/id :block/updated-at) stale-block-chunk)
                delete-labels (into-array (keep :logseq.property.embedding/hnsw-label stale-block-chunk))
                added-labels (worker-util/profile :text-embedding
                               (c.m/<?
                                (.text-embedding+store!
                                 infer-worker repo (into-array (map :block.temp/text-to-embedding stale-block-chunk))
                                 delete-labels false)))
                tx-data (labels-update-tx-data @conn e+updated-at-coll added-labels)]
            (d/transact! conn tx-data)
            (m/? (task--update-index-info! repo infer-worker))))
        (c.m/<? (.write-index! infer-worker repo))))))

(defn task--re-embedding-graph-data!
  "force re-embedding all block-data in graph"
  [repo conn]
  (m/sp
    (when-let [^js infer-worker @worker-state/*infer-worker]
      (c.m/<? (.force-reset-index! infer-worker repo))
      (m/? (task--update-index-info! repo infer-worker))
      (let [all-blocks (stale-block-lazy-seq @conn true)]
        (doseq [block-chunk (sequence (partition-by-text-size 2000) all-blocks)]
          (let [e+updated-at-coll (map (juxt :db/id :block/updated-at) block-chunk)
                added-labels (worker-util/profile :text-embedding
                               (c.m/<?
                                (.text-embedding+store!
                                 infer-worker repo (into-array (map :block.temp/text-to-embedding block-chunk))
                                 nil false)))
                tx-data (labels-update-tx-data @conn e+updated-at-coll added-labels)]
            (d/transact! conn tx-data)
            (m/? (task--update-index-info! repo infer-worker)))))
      (c.m/<? (.write-index! infer-worker repo)))))

(defn- remove-outdated-hnsw-label!
  [conn es]
  (when (seq es)
    (d/transact!
     conn (mapcat
           (fn [e]
             [[:db.fn/retractAttribute e :logseq.property.embedding/hnsw-label]
              [:db.fn/retractAttribute e :logseq.property.embedding/hnsw-label-updated-at]])
           es))))

(defn task--search
  [repo conn query-string nums-neighbors]
  (m/sp
    (when-let [^js infer-worker @worker-state/*infer-worker]
      (let [{:keys [distances neighbors] :as r}
            (worker-util/profile (str "search: '" query-string "'")
              (js->clj (c.m/<? (.search infer-worker repo query-string nums-neighbors)) :keywordize-keys true))
            labels (->> (map vector distances neighbors)
                        (keep (fn [[distance label]] (when-not (js/isNaN distance) label))))
            datoms (map (fn [label]
                          (->> label
                               (d/datoms @conn :avet :logseq.property.embedding/hnsw-label)
                               (sort-by :tx >))) labels)
            result-es (keep (comp :e first) datoms)
            es-with-outdated-hnsw-label (map :e (mapcat next datoms))
            blocks (map #(select-keys (assoc (d/entity @conn %) :block.temp/search? true)
                                      [:db/id :block/title :logseq.property.embedding/hnsw-label]) result-es)]
        (remove-outdated-hnsw-label! conn es-with-outdated-hnsw-label)
        (prn :query-result r)
        (pp/print-table ["id" "hnsw-label" "title"] (map #(-> %
                                                              (update-keys name)
                                                              (update-vals (fn [v]
                                                                             (if (and (string? v) (> (count v) 60))
                                                                               (str (subs v 0 60) "[TRUNCATED]")
                                                                               v))))
                                                         blocks))))))

(def ^:private vector-search-state-flow
  (m/eduction
   (map (fn [m] (-> m
                    (update :index-building-canceler keys)
                    (update :search-canceler keys))))
   (c.m/throttle 300 (m/watch *vector-search-state))))

(when-not common-config/PUBLISHING ; NOTE: we may support vector-search in publishing mode later
  (c.m/run-background-task
   ::subscribe-state
   (m/reduce
    (fn [_ m] (worker-util/post-message :vector-search-sync-state m))
    vector-search-state-flow)))

(comment
  (def repo (frontend.worker.state/get-current-repo))
  (def conn (frontend.worker.state/get-datascript-conn (frontend.worker.state/get-current-repo)))
  (.force-reset-index! @worker-state/*infer-worker repo)
  ((task--embedding-stale-blocks! repo conn) prn js/console.log)
  ((task--re-embedding-graph-data! repo conn) prn js/console.log)

  ((task--search repo conn "perf performance datomic stat" 10) prn js/console.log))
