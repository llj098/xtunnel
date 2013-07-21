(ns xtunnel-local.core
  (:use aleph.tcp
        lamina.core
        gloss.core
        gloss.io))

(def local-info (ref {}))


(defn init-local-info []
  (dosync (alter local-info assoc
                 :local-conf {}
                 :backend-conf {:server-name "ln.fatlj.me" :server-port 2000}
                 :backend-channel (atom nil)
                 :listen-channel (atom nil)
                 :connection-map (ref {})
                 :client-id (java.util.UUID/randomUUID)
                 :client-id (atom 1))))

(defcodec fr (finite-frame
             :uint32
             {:client-id :int64 :id :uint32 :cmd :byte :body (repeated :byte) }))

(defn server-handler [channel client-info]
  (let [cid (inc (swap! (:client-id @local-info) inc))]
    (dosync (alter (:connection-map @local-info) assoc cid channel))
    (on-closed channel
               #(dosync (alter (:connection-map @local-info) dissoc cid)))
    (receive-all channel
                 #(enqueue @(:backend-channel @local-info) "hello" %))))

(defn start []
  (init-local-info)
  (let [up-channel (wait-for-result (tcp-client (:backend-conf @local-info)) 5000)]
    (swap! (:backend-channel @local-info)  (fn [_] up-channel))
    (receive-all @(:backend-channel @local-info)
                 #(enqueue (first (vals @(:connection-map @local-info))) %))
    (on-closed up-channel #(swap! (:backend-channel @local-info) (fn [_] nil)))
    (swap! (:listen-channel @local-info)
           (fn [_] (start-tcp-server server-handler {:port 9999})))))


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


(defn init-socks-channel [ch sock-ch client-id connection-id]
  (on-closed
   sock-ch
   (enqueue
    ch
    (encode fr {:client-id client-id
                :id connection-id
                :cmd 0 :body []})))

  (receive-all
   sock-ch
   (fn [d] (enqueue
           channel
           (encode fr {:client-id client-id
                       :id connection-id
                       :cmd 1 :body d})))))


(defn remote-server-handler [channel client-info]

  (receive-all
   channel
   #(;;first connection first xtunnel-local
     (let [client-id (:client-id %)
           connection-id (:id %)
           conn-map (getadd-conn-map client-id)
           ch (get @conn-map (:id %))]
       (if ch
         (if (zero? (:cmd %))
           (remote-close-channle ch conn-map connection-id)
           (enqueue ch (:body %)))
         (let [sock-ch
               (wait-for-result
                (tcp-client @(:socks-conf @remote-info)))]
           (init-socks-channel ch sock-ch client-id connection-id)
           (enqueue sock-ch (:body %)))))))

  (on-closed channel nil))

(defn start-remote []
  (init-remote-info)
  (swap! (:listen-channel @remote-info)
         (fn [_] (start-tcp-server remote-server-handler {:port 9870 :frame fr}))))
