(ns ^:no-doc kee-frame.websocket
  #?(:cljs
     (:require-macros
       [cljs.core.async.macros :refer [go go-loop]]))
  (:require
    [clojure.core.async :refer [<! >! chan close!]]
    #?(:clj
    [clojure.core.async :refer [go go-loop]])
    [re-frame.core :as rf]
    [kee-frame.core :as k]
    [kee-frame.interop :as interop]))

(defn date []
  #?(:clj  (java.util.Date.)
     :cljs (js/Date.)))

(defn- receive-messages! [ws-chan {:keys [path dispatch]
                                   :as   socket-config}]
  (go-loop []
    (if-let [message (<! ws-chan)]
      (do (when false (rf/dispatch [::log path :received (date) message]))
          (rf/dispatch [dispatch message])
          (recur))
      (rf/dispatch [::disconnected socket-config]))))

(defn- send-messages!
  [path buffer-chan ws-chan wrap-message]
  (go-loop []
    (when-let [message (<! buffer-chan)]                    ;; buffer-chan may be closed
      (if (>! ws-chan (wrap-message message))               ;; if ws-chan is closed put message back in buffer-chan
        (do (when false (rf/dispatch [::log path :sent (date) message]))
            (recur))
        (>! buffer-chan message)))))

(defn- socket-for [db path]
  (or
    (get-in db [::sockets path])
    (let [buffer-chan (chan)
          ref {:buffer-chan buffer-chan}]
      (rf/dispatch [::created path buffer-chan])
      ref)))

(defn start-websocket [create-socket {:keys [path format]
                                      :as   socket-config}]
  (go
    (let [url (interop/websocket-url path)
          {:keys [ws-channel error]} (<! (create-socket url {:format format}))]
      (if error
        (rf/dispatch [::error path error])
        (rf/dispatch [::connected ws-channel socket-config])))))

(defn- socket-not-found [path websockets]
  (throw (ex-info (str "No socket registered for path " path) websockets)))

(k/reg-event-db
  ::created
  (fn [db [path buffer-chan]]
    (assoc-in db [::sockets path] {:buffer-chan buffer-chan
                                   :state       :initializing})))

(k/reg-event-db
  ::log
  (fn [db [path type time message]]
    (update-in db [::sockets path :log] conj {:time    time
                                              :message message
                                              :type    type})))

(k/reg-event-db
  ::error
  (fn [db [path message]]
    (update-in db [::sockets path] merge {:state   :error
                                          :message message})))

(k/reg-event-fx
  ::disconnected
  (fn [{:keys [db]} [{:keys [path reconnect?]
                      :as   socket-config}]]
    (when (get-in db [::sockets path])
      (merge
        {:db (update-in db [::sockets path] merge {:state :disconnected})}
        (when reconnect?
          {::open socket-config})))))

(k/reg-event-fx
  ::connected
  (fn [{:keys [db]} [ws-chan {:keys [path wrap-message]
                              :as   socket-config}]]
    (let [{:keys [buffer-chan]} (socket-for db path)]
      (send-messages! path buffer-chan ws-chan wrap-message)
      (receive-messages! ws-chan socket-config)
      {:db (update-in db [::sockets path] merge {:ws-channel ws-chan
                                                 :state      :connected})})))

(rf/reg-fx ::open (partial start-websocket interop/create-socket))

(k/reg-event-fx ::close (fn [{:keys [db]} [path]]
                          (if-let [{:keys [buffer-chan ws-channel]} (socket-for db path)]
                            (do
                              (close! ws-channel)
                              (close! buffer-chan))
                            (socket-not-found path (::sockets db)))
                          {:db (assoc-in db [::sockets path] nil)}))

(k/reg-event-fx ::send (fn [{:keys [db]} [path message]]
                         (if-let [buffer-chan (:buffer-chan (socket-for db path))]
                           (do (go (>! buffer-chan message))
                               (when false {:dispatch [::log path :buffered (date) message]}))
                           (socket-not-found path (::sockets db)))))

(rf/reg-sub ::state (fn [db [_ path]]
                      (get-in db [::sockets path])))