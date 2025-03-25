(ns frontend.db.async.util
  "Async util helper"
  (:require [frontend.state :as state]
            [promesa.core :as p]
            [frontend.db.conn :as db-conn]
            [datascript.core :as d]
            [logseq.db :as ldb]))

(defn <q
  [graph {:keys [transact-db?]
          :or {transact-db? true}
          :as opts} & inputs]
  (assert (not-any? fn? inputs) "Async query inputs can't include fns because fn can't be serialized")
  (when-let [worker @state/*db-worker]
    (let [*async-queries (:db/async-queries @state/state)
          async-requested? (get @*async-queries [inputs opts])]
      (if async-requested?
        (let [db (db-conn/get-db graph)]
          (apply d/q (first inputs) db (rest inputs)))
        (p/let [result (worker :general/q graph (ldb/write-transit-str inputs))]
          (swap! *async-queries assoc [inputs opts] true)
          (when result
            (let [result' (ldb/read-transit-str result)]
              (when (and transact-db? (seq result') (coll? result'))
                (when-let [conn (db-conn/get-db graph false)]
                  (let [tx-data (->>
                                 (if (and (coll? (first result'))
                                          (not (map? (first result'))))
                                   (apply concat result')
                                   result')
                                 (remove nil?))]
                    (if (every? map? tx-data)
                      (try
                        (d/transact! conn tx-data)
                        (catch :default e
                          (js/console.error "<q failed with:" e)
                          nil))
                      (js/console.log "<q skipped tx for inputs:" inputs)))))
              result')))))))

(defn <pull
  ([graph id]
   (<pull graph '[*] id))
  ([graph selector id]
   (when-let [worker @state/*db-worker]
     (p/let [result (worker :general/pull graph (ldb/write-transit-str selector) (ldb/write-transit-str id))]
       (when result
         (let [result' (ldb/read-transit-str result)]
           (when-let [conn (db-conn/get-db graph false)]
             (d/transact! conn [result']))
           result'))))))
