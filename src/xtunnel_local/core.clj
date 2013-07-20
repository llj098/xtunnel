(ns xtunnel-local.core
  (:use aleph.tcp
        lamina.core))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(def local-info (ref {}))

(defn init-local-info []
  (dosync (alter local-info assoc
                 :backend-conf {:server-name "ln.fatlj.me" :server-port 2000}
                 :backend-channel (atom nil)
                 :listen-channel (atom nil)
                 :connection-map (ref {})
                 :uuid (java.util.UUID/randomUUID)
                 :connection-id (atom 1))))


(defn server-handler [channel client-info]
  (let [cid (inc (swap! (:connection-id @local-info) inc))]
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
