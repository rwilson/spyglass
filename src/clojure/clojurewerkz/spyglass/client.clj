(ns clojurewerkz.spyglass.client
  "Key Memcached client operations"
  (:refer-clojure :exclude [set get flush replace])
  (:import [net.spy.memcached MemcachedClient ConnectionFactory DefaultConnectionFactory
            BinaryConnectionFactory KetamaConnectionFactory AddrUtil ConnectionFactoryBuilder
            FailureMode ConnectionFactoryBuilder$Protocol CASValue ClientMode]
           [net.spy.memcached.transcoders Transcoder SerializingTranscoder]
           [clojurewerkz.spyglass OperationFuture BulkGetFuture GetFuture]
           [net.spy.memcached.auth AuthDescriptor PlainCallbackHandler]))


;;
;; Implementation
;;
(defn- servers
  [^String server-list]
  (AddrUtil/getAddresses server-list))

(defprotocol AsFailureMode
  (^FailureMode to-failure-mode [input]))

(extend-protocol AsFailureMode
  FailureMode
  (to-failure-mode [input]
    input)

  String
  (to-failure-mode [input]
    (case input
      "redistribute" FailureMode/Redistribute
      "retry"        FailureMode/Retry
      "cancel"       FailureMode/Cancel))

  clojure.lang.Named
  (to-failure-mode [input]
    (to-failure-mode (name input))))

(defprotocol AsClientMode
  (^ClientMode to-client-mode [input]))

(extend-protocol AsClientMode
  ClientMode
  (to-client-mode [input]
    input)

  String
  (to-client-mode [input]
    (case input
      "dynamic" ClientMode/Dynamic
      "static"  ClientMode/Static))

  clojure.lang.Named
  (to-client-mode [input]
    (to-client-mode (name input))))

(defn- ^ConnectionFactory customize-factory
  "Param `client-mode` defaults to `:static` unless specified, in contrast to the
  underlying AWS ElastiCache client, which defaults to `:dynamic`. Defaultling to
  `:dynamic`, however, prevents the client from working against any server-list
  except for a configuration endpoint. So, rather than sprinkling `:static` mode
  throughout the codebase (tests and all), we default to `:static` where all the
  `ConnectionFactory` instances are configured, and require callers to explicitly
  enable `:dynamic` mode when constructing a connection."
  [^ConnectionFactory cf
   {:keys [client-mode failure-mode transcoder auth-descriptor timeout-exception-threshold]
    :or   {client-mode :static}}]
  (let [;; Houston, we have a *FactoryFactory here!
        cfb (ConnectionFactoryBuilder. cf)]
    (when client-mode
      (.setClientMode cfb (to-client-mode client-mode)))
    (when failure-mode
      (.setFailureMode cfb (to-failure-mode failure-mode)))
    (if transcoder
      (.setTranscoder cfb transcoder)
      ;;otherwise use default serializer
      (.setTranscoder cfb (SerializingTranscoder.)))
    (when auth-descriptor
      (.setAuthDescriptor cfb auth-descriptor))
    (when timeout-exception-threshold
      (.setTimeoutExceptionThreshold cfb timeout-exception-threshold))
    ;; ConnectionFactoryBuilder will use various CF properties
    ;; from the argument you give it but protocol is not one of
    ;; them, so we set it up here explicitly. MK.
    (when (instance? BinaryConnectionFactory cf)
      (.setProtocol cfb ConnectionFactoryBuilder$Protocol/BINARY))
    (.build cfb)))

(defn- cas-value->map
  "Given a CASValue, transform it to a map with keys #{:cas :value}"
  [^CASValue cas-value]
    {:value (when cas-value (.getValue cas-value))
     :cas   (when cas-value (.getCas   cas-value))})


;;
;; API
;;

(defn ^ConnectionFactory text-connection-factory
  [& {:as opts}]
  (customize-factory (DefaultConnectionFactory.) opts))

(defn ^ConnectionFactory bin-connection-factory
  [& {:as opts}]
  (customize-factory (BinaryConnectionFactory.) opts))

(defn ^ConnectionFactory ketama-connection-factory
  [& {:as opts}]
  (customize-factory (KetamaConnectionFactory.) opts))

(defn auth-descriptor
  ([^String username ^String password]
     (AuthDescriptor/typical username password))
  ([^String username ^String password mechanism]
     (if-not (contains? #{:plain :cram-md5} mechanism)
       (auth-descriptor)
       (AuthDescriptor. (into-array java.lang.String
                                    [(clojure.string/upper-case (name mechanism))])
                        (PlainCallbackHandler. username password)))))

(defn text-connection
  "Returns a new text protocol client that will use the provided list of servers."
  ([^String server-list]
     (text-connection server-list (text-connection-factory)))
  ([^String server-list ^DefaultConnectionFactory cf]
     (MemcachedClient. cf (servers server-list)))
  ([^String server-list ^String username ^String password]
     (let [ad (auth-descriptor username password)]
       (MemcachedClient. (text-connection-factory :auth-descriptor ad) (servers server-list)))))

(defn bin-connection
  "Returns a new binary protocol client that will use the provided list of servers."
  ([^String server-list]
     (bin-connection server-list (bin-connection-factory)))
  ([^String server-list ^BinaryConnectionFactory cf]
     (MemcachedClient. cf (servers server-list)))
  ([^String server-list ^String username ^String password]
     (let [ad (auth-descriptor username password)]
       (MemcachedClient. (bin-connection-factory :auth-descriptor ad) (servers server-list))))
  ([^String server-list ^String username ^String password auth-type]
     (let [ad (auth-descriptor username password auth-type)]
       (MemcachedClient. (bin-connection-factory :auth-descriptor ad) (servers server-list)))))

(defn ^clojurewerkz.spyglass.OperationFuture flush
  "Flush all caches from all servers. One-arity version flushes all caches
   immediately, two-arity version does it after the provided delay in seconds."
  ([^MemcachedClient client]
     (OperationFuture. (.flush client)))
  ([^MemcachedClient client ^long delay]
     (OperationFuture. (.flush client delay))))

(defn ^clojurewerkz.spyglass.OperationFuture set
  "Set an object in the cache (using the default transcoder) regardless of any existing value."
  ([^MemcachedClient client ^String key ^long expiration value]
     (OperationFuture. (.set client key expiration value)))
  ([^MemcachedClient client ^String key expiration value transcoder]
     (OperationFuture. (.set client key expiration value transcoder))))

(defn get
  "Get with a single key."
  ([^MemcachedClient client ^String key]
     (.get client key))
  ([^MemcachedClient client ^String key ^Transcoder transcoder]
     (.get client key transcoder)))

(defn ^clojurewerkz.spyglass.GetFuture
  async-get
  "Get the given key asynchronously"
  ([^MemcachedClient client ^String key]
     (GetFuture. (.asyncGet client key)))
  ([^MemcachedClient client ^String key ^Transcoder transcoder]
     (GetFuture. (.asyncGet client key transcoder))))

(defn get-and-touch
  "Get a single key and reset its expiration."
  ([^MemcachedClient client ^String key ^long expiration]
     (cas-value->map (.getAndTouch client key expiration)))
  ([^MemcachedClient client ^String key ^long expiration ^Transcoder transcoder]
     (cas-value->map (.getAndTouch client key expiration transcoder))))

(defn async-get-and-touch
  "Get a single key and reset its expiration asynchronously"
  ([^MemcachedClient client ^String key ^long expiration]
     (OperationFuture. (.asyncGetAndTouch client key expiration)))
  ([^MemcachedClient client ^String key ^long expiration ^Transcoder transcoder]
     (OperationFuture. (.asyncGetAndTouch client key expiration transcoder))))

(defn ^clojure.lang.IPersistentMap get-multi
  "Get the values for multiple keys from the cache. Returns a future that will return a mutable map of results."
  ([^MemcachedClient client ^java.util.Collection keys]
     (into {} (.getBulk client keys)))
  ([^MemcachedClient client ^java.util.Collection keys ^Transcoder transcoder]
     (into {} (.getBulk client keys transcoder))))

(defn ^clojurewerkz.spyglass.BulkGetFuture
  async-get-multi
  "Get the values for multiple keys from the cache. Returns a future that will return a map of results."
  ([^MemcachedClient client ^java.util.Collection keys]
     (BulkGetFuture. (.asyncGetBulk client keys)))
  ([^MemcachedClient client ^java.util.Collection keys ^Transcoder transcoder]
     (BulkGetFuture. (.asyncGetBulk client keys transcoder))))

(defn ^clojurewerkz.spyglass.OperationFuture delete
  "Delete the given key from the cache."
  [^MemcachedClient client ^String key]
  (OperationFuture. (.delete client key)))

(defn ^clojurewerkz.spyglass.OperationFuture touch
  "Touch the given key to reset its expiration time."
  [^MemcachedClient client ^String key ^long expiration]
  (OperationFuture. (.touch client key expiration)))

(defn ^clojurewerkz.spyglass.OperationFuture add
  "Add an object to the cache (using the default transcoder) if it does not exist already.
   Returns a future that will return false if a mutation did not occur, true otherwise."
  [^MemcachedClient client ^String key ^long expiration value]
  (OperationFuture. (.add client key expiration value)))

(defn ^clojurewerkz.spyglass.OperationFuture replace
  "Replace an object to the cache (using the default transcoder) if there is already a value for the given key.
   Returns a future that will return false if a mutation did not occur (the key was missing), true otherwise."
  [^MemcachedClient client ^String key ^long expiration value]
  (OperationFuture. (.replace client key expiration value)))

(defn incr
  "Increment the given key by the given amount. Returns -1 if the value is missing."
  ([^MemcachedClient client ^String key ^long by]
     (.incr client key by))
  ([^MemcachedClient client ^String key ^long by ^long default]
     (.incr client key by default))
  ([^MemcachedClient client ^String key by default expiration]
     (.incr client key by default expiration)))

(defn decr
  "Decrement the given key by the given amount. Returns -1 if the value is missing."
  ([^MemcachedClient client ^String key ^long by]
     (.decr client key by))
  ([^MemcachedClient client ^String key ^long by ^long default]
     (.decr client key by default))
  ([^MemcachedClient client ^String key by default expiration]
     (.decr client key by default expiration)))

(defn gets
  "Gets (with CAS support) a single key."
  ([^MemcachedClient client ^String key]
     (cas-value->map (.gets client key)))
  ([^MemcachedClient client ^String key transcoder]
     (cas-value->map (.gets client key transcoder))))

(defn cas
  "Perform a synchronous CAS (compare-and-swap) operation."
  ([^MemcachedClient client ^String key ^long cas-id value]
     (keyword (.toLowerCase (str (.cas client key cas-id value)))))
  ([^MemcachedClient client ^String key cas-id value transcoder]
     (keyword (.toLowerCase (str (.cas client key cas-id value transcoder)))))
  ([^MemcachedClient client ^String key cas-id expiration value transcoder]
     (keyword (.toLowerCase (str (.cas client key cas-id expiration value transcoder))))))

(defn ^clojurewerkz.spyglass.OperationFuture async-cas
  "Perform an asynchronous CAS (compare-and-swap) operation."
  ([^MemcachedClient client ^String key ^long cas-id value]
     (OperationFuture. (.asyncCAS client key cas-id value)))
  ([^MemcachedClient client ^String key cas-id value transcoder]
     (OperationFuture. (.asyncCAS client key cas-id value transcoder)))
  ([^MemcachedClient client ^String key cas-id expiration value transcoder]
     (OperationFuture. (.asyncCAS client key cas-id expiration value transcoder))))

(defn get-versions
  "Get the versions of all of the connected Memcached instances."
  [^MemcachedClient client]
  (into {} (.getVersions client)))

(defn get-stats
  "Returns stats from all of the connections."
  ([^MemcachedClient client]
     (into {} (.getStats client)))
  ([^MemcachedClient client ^String stat]
     (into {} (.getStats client stat))))

(defn shutdown
  "Shuts down the client. One-arity forces immediate shutdown. Three-arity shuts down gracefully
   (lets in-progress operations to finish, up to the given amount of time)."
  ([^MemcachedClient client]
     (.shutdown client))
  ([^MemcachedClient client ^long timeout ^java.util.concurrent.TimeUnit time-unit]
     (.shutdown client timeout time-unit)))

(defn set-log-level!
  "Sets log level, assuming JDK logger (that is, not Log4J) is used.

   Valid log levels:

     * \"SEVERE\"
     * \"WARNING\"
     * \"INFO\"
     * \"CONFIG\"
     * \"FINE\"
     * \"FINER\"
     * \"FINEST\"

  For more information about JDK loggers, see JDK documentation at
  http://docs.oracle.com/javase/7/docs/api/java/util/logging/Logger.html"
  [level]
  (let [lgr (java.util.logging.Logger/getLogger "net.spy.memcached")
        lvl (java.util.logging.Level/parse (-> level clojure.core/name .toUpperCase))]
    (.setLevel lgr lvl)
    lgr))
