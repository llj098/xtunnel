(ns xtunnel-local.core
  (:use aleph.tcp
        lamina.core
        gloss.core
        gloss.io))

(def local-info (ref {}))


(defn mirror-handler [ch info]
  (join ch ch))

(defn start-mirror []
  (start-tcp-server mirror-handler {:port 9999}))

(defcodec fr (finite-frame :uint32
                           (ordered-map
                             :client-id :int32
                             :id :uint32
                             :cmd :byte
                             :body (repeated :byte :prefix :none) )))

(defn init-local-info []
  (dosync (alter local-info assoc
                 :local-conf {}
                 :backend-conf {:server-name "localhost"
                                :server-port 9999
                                :frame fr}
                 :connection-map (ref {})
                 :client-id (atom 0))))

(defn local-handler [channel client-info]
  (println "new connection coming")
  (let [cid (swap! (:client-id @local-info) inc)]
    (receive-all channel
                 #(enqueue
                   (:remote-channel @local-info)
                   {:client-id 1 :cmd 2 :id cid :body (seq (.array %))}))
    (on-closed channel
               #(dosync (alter (:connection-map @local-info) dissoc cid)))
    (dosync (alter (:connection-map @local-info) assoc cid channel))))

(defn timer [interval fn & args]
  (future (doseq [f (repeatedly #(apply fn args))]
            (Thread/sleep interval))))

(defn start-local! []
  (init-local-info)
  (if-let [remote-channel (wait-for-result
                    (tcp-client (:backend-conf @local-info))
                    5000)]
    (do
      (dosync (alter local-info assoc :remote-channel remote-channel))
      (receive-all remote-channel
                   #((println %)
                     (if-let [client-ch (get @(:connection-map @local-info)
                                             (:id %))]
                       (enqueue client-ch (byte-array (map byte (:body %)))))))

      (if-let [stop-fn (start-tcp-server local-handler {:port 9996})]
        (dosync (alter local-info assoc :stop-listen stop-fn))))))

(defn stop-local! []
  (if-let [ch (:remote-channel @local-info)]
    (close ch))

  (if-let [stop-fn (:stop-listen @local-info)]
    (stop-fn)))


;;message frame between xtunnel-local and xtunnel-server
;; client-id:int16 id:byte len:int32 payload:variant

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remote logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def remote-info (ref {}))

(defn init-remote-info []
  (dosync
   (alter remote-info assoc
          :socks-conf (ref {:server-port 1080 :server-name "localhost"})
          :remote-conf (ref {})
          :listen-channel (atom nil)
          :client-map (ref {})))) ;;  {:client-id (ref {:id xxxx :channel xxx}) }


(defn getadd-conn-map [client-id]
  (if-not (contains? @(:client-map @remote-info) client-id)
    (dosync
     (alter (:client-map @remote-info)
            assoc client-id (ref {}))))
  (get @(:client-map @remote-info) client-id))


(defn remote-close-channle [peer cmap cid]
  (close peer)
  (dosync (alter cmap dissoc cid)))


(defn init-socks-channel [ch sock-ch connection-id]
  (on-closed
   sock-ch
   (enqueue
    ch
    (encode fr {:client-id ch
                :id connection-id
                :cmd 0 :body []})))

  (receive-all
   sock-ch
   (fn [d] (enqueue
           channel
           (encode fr {:client-id ch
                       :id connection-id
                       :cmd 1 :body d})))))

(defn remote-server-handler [channel client-info]
  (receive-all
   channel
   #((let [connection-id (:id %)
           conn-map (getadd-conn-map channel)
           ch (get @conn-map (:id %))]
       (if ch
         (if (zero? (:cmd %))
           (remote-close-channle ch conn-map connection-id)
           (enqueue ch (:body %)))
         (let [sock-ch
               (wait-for-result
                (tcp-client @(:socks-conf @remote-info)))]
           (init-socks-channel ch sock-ch connection-id)
           (enqueue sock-ch (:body %)))))))

  (on-closed  channel
              #(doseq [sock-ch
                       (vals (get (:client-map @remote-info)
                                  channel))]
                 (close sock-ch)))

  (getadd-conn-map channel))

(defn start-remote []
  (init-remote-info)
  (swap! (:listen-channel @remote-info)
         (fn [_] (start-tcp-server
                 remote-server-handler {:port 9870 :frame fr}))))
