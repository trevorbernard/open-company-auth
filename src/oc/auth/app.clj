(ns oc.auth.app
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [cheshire.core :as json]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [raven-clj.ring :as sentry-mw]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [compojure.core :as compojure :refer (GET POST DELETE)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cors :refer (wrap-cors)]
            [buddy.auth.middleware :refer (wrap-authentication)]
            [buddy.auth.backends :as backends]
            [ring.util.response :refer (redirect)]
            [com.stuartsierra.component :as component]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.auth.components :as components]
            [oc.auth.config :as c]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]
            [oc.auth.ring :as ring]
            [oc.auth.slack :as slack]
            [oc.auth.email :as email]
            [oc.auth.user :as user]))

;; Send unhandled exceptions to Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

;; ----- Response Functions -----

(def ^:private test-token {:test "test" :bago "bago"})

(defonce ^:private test-response (ring/text-response  "OpenCompany auth server: OK" 200))

(defonce ^:private unauth-response
  (ring/text-response "" 401))

(defn- jwt-debug-response
  "Helper to format a JWT debug response"
  [payload]
  (let [jwt-token (jwt/generate payload)
        response {
          :jwt-token jwt-token
          :jwt-verified (jwt/check-token jwt-token)
          :jwt-decoded (jwt/decode jwt-token)}]
    (ring/json-response response 200)))

(defn- redirect-to-web-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."
  [[success? jwt-or-reason]]
  (if success?
    (redirect (str c/ui-server-url "/login?jwt=" jwt-or-reason))
    (redirect (str c/ui-server-url "/login?access=" jwt-or-reason))))

(defn- email-auth-response
  "Return a JWToken for the email auth'd user, or a 401 Unauthorized."
  ([sys req] (email-auth-response sys req false))
  
  ([sys req location]
  (if-let [email (:identity req)] ; email/pass sucessfully auth'd
    (pool/with-pool [conn (-> sys :db-pool :pool)]
      (if-let* [user (user/get-user-by-email conn email) ; user from DB for JWToken
                clean-user (dissoc user :created-at :updated-at :password-hash)
                sourced-user (assoc clean-user :auth-source "email")]
        ; respond with JWToken
        (let [headers (if location {"Location" (email/user-url (:org-id user) (:user-id user))} {})
              status (if location 201 200)]
          (ring/text-response (jwt/generate sourced-user) status headers))
        unauth-response)) ; couldn't get the auth'd user (unexpected)
    unauth-response))) ; email/pass didn't auth (expected)

(defn- user-enumeration-response
  "Return a JSON collection of users for the org."
  [users org-id url]
  (let [response {:collection {
                  :version "1.0"
                  :href url
                  :org-id org-id
                  :links [(hateoas/self-link url "application/vnd.collection+vnd.open-company.user+json;version=1")]
                  :users users}}]
  (ring/json-response response 200 "application/vnd.collection+vnd.open-company.user+json;version=1")))

(defn- invite-response
  "Return a JSON response for the user that was just invited/re-invited."
  [user]
  (let [user-response (select-keys user [:user-id :real-name :avatar :email :status])]
    (ring/json-response (email/user-links user) 201 {"Location" (email/user-url (:org-id user) (:user-id user))})))

;; ----- JWToken auth'ing macro -----

(defmacro with-valid-token
  "TODO: not working yet."
  [[req] & body]
  '(if-let [token#    (jwt/read-token (:headers ~req))]
    (if-let [decoded#  (jwt/decode token#)]
      (let [user-id   (-> decoded# :claims :user-id)
            user-tkn (-> decoded :claims :user-token)
            org-id    (-> decoded# :claims :org-id)]
        (do ~@body))
      (do
        (timbre/warn "Bad token for request")      
        (ring/error-response "Could note confirm token." 401)))
    (do
      (timbre/warn "No token for request")      
      (ring/error-response "Could note confirm token." 401))))

;; ----- Request Handling Functions -----

(defn- auth-settings [req]
  (if-let* [token (jwt/read-token (:headers req))
            decoded (jwt/decode token)
            org-id (-> decoded :claims :org-id)
            user-id (-> decoded :claims :user-id)]
    ;; auth'd, give settings specific to their authentication source
    (let [authed-settings (if (= (-> decoded :claims :auth-source) "email")
                            (email/authed-settings org-id user-id)
                            (slack/authed-settings org-id user-id))]
      (ring/json-response authed-settings 200))
    ;; not auth'd, give them both settings
    (ring/json-response 
      {:slack slack/auth-settings
       :email email/auth-settings} 200)))

(defn- user-delete [sys req prefix]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    (pool/with-pool [conn (-> sys :db-pool :pool)]
      (if-let* [del-user-id (-> req :params :user-id)
                _valid-prefix (s/starts-with? del-user-id prefix)
                del-user (user/get-user conn del-user-id)] ; user to delete
        (do (timbre/info "Delete request for" del-user-id)
          (if (= (:org-id del-user) org-id) ; member of the same org
            (do (timbre/info "Deleting " del-user-id)
                (user/delete-user! conn del-user-id)
                (ring/text-response "" 204)) ; All good
            (do (timbre/warn "Unauth'd delete request of" del-user-id)
                (ring/error-response nil 401))))
        (do (timbre/warn "No user for delete request")
            (ring/error-response nil 404))))
    (do (timbre/warn "Bad token for request")      
        (ring/error-response "Could note confirm token." 401))))

;; ----- Slack Request Handling Functions -----

(defn- oauth-callback [callback params]
  (if (get params "test")
    (ring/json-response {:test true :ok true} 200)
    (redirect-to-web-ui (callback params))))

(defn- refresh-slack-token [req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            user-tkn (-> decoded :claims :user-token)
            org-id   (-> decoded :claims :org-id)]
    (do (timbre/info "Refresh token request for user" user-id "of org" org-id)
      (if (slack/valid-access-token? user-tkn)
        (do
          (timbre/info "Refreshing token" user-id)
          (ring/text-response (jwt/generate (merge (:claims decoded)
                                                   (store/retrieve org-id)
                                                   {:auth-source "slack"})) 200))
        (do
          (timbre/warn "Invalid access token for" user-id)            
          (ring/error-response "Could note confirm token." 400))))
    (do
      (timbre/warn "Bad refresh token request")      
      (ring/error-response "Could note confirm token." 400))))

;; ----- Email Request Handling Functions -----

(defn- refresh-email-token [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    (do (timbre/info "Refresh token request for user" user-id " of org" org-id)
      (pool/with-pool [conn (-> sys :db-pool :pool)]
        (let [user (user/get-user conn user-id)]
          (if (and user (= org-id (:org-id user))) ; user still present in the DB and still member of the org
            (do 
              (timbre/info "Refreshing token" user-id)
              (ring/text-response (jwt/generate (merge (:claims decoded) {:auth-source "email"})) 200))
            (do
              (timbre/warn "No user or org-id match for token refresh of" user-id)            
              (ring/error-response "Could note confirm token." 400))))))
    (do
      (timbre/warn "Bad refresh token request")      
      (ring/error-response "Could note confirm token." 400))))

(defn- email-user-create [sys req]
  ;; check if the request is well formed JSON
  (if-let* [post-body (:body req)
            json-body (slurp post-body)
            map-body (try (json/parse-string json-body) (catch Exception e false))
            dirty-body (keywordize-keys map-body)
            body (dissoc dirty-body :user-id :org-id)] ; tsk, tsk
    ;; request is well formed JSON, check if it's valid
    (if-let* [email (:email body)
              password (:password body)]
      ;; request is valid, check if the user already exists
      (do (timbre/info "User create request for" email)
          (pool/with-pool [conn (-> sys :db-pool :pool)]
            (if-let [prior-user (user/get-user-by-email conn email)]
              (do (timbre/warn "User already exists with email" email)
                  (ring/error-response "User with email already exists." 409)) ; already exists
              (if-let [user (email/create-user! conn (email/->user body password))] ; doesn't exist, so create the user
                (do (timbre/info "Creating user" email)
                    (email-auth-response sys (assoc req :identity email) true)) ; respond w/ JWToken and location
                (do (timbre/error "Failed creating user" email)
                    (ring/error-response "" 500))))))
      (do (timbre/warn "Invalid request body")
          (ring/error-response "Invalid request body." 400))) ; request not valid
    (do (timbre/warn "Could not parse request body")
        (ring/error-response "Could not parse request body." 400)))) ; request not well formed

(defn- email-user-enumerate [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    (pool/with-pool [conn (-> sys :db-pool :pool)]
      (if-let [users (email/users-links conn org-id)] ; list of all users in the org
        ;; Remove the requesting user from the list and respond
        (do (timbre/info "User enumeration for" org-id)
            (println users)
            (user-enumeration-response (filter #(not= (:user-id %) user-id) users)
                                       org-id
                                       (:href (email/enumerate-link org-id))))
        (do
          (timbre/warn "No org for" org-id)
          (user-enumeration-response [] org-id "/email/users"))))
    (do
      (timbre/warn "Bad token for request")      
      (ring/error-response "Could note confirm token." 401))))

(defn- email-user-invite [sys req]
  ;; check if the request is auth'd
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    ;; request is auth'd, check if the request is well formed JSON
    (if-let* [post-body (:body req)
              json-body (slurp post-body)
              map-body (try (json/parse-string json-body) (catch Exception e false))
              body (keywordize-keys map-body)]
      ;; request is well formed JSON, check if it's valid
      (if-let* [email (:email body)
                company-name (:company-name body)
                logo (:logo body)]
        (do (timbre/info "Invite request for" email)
          (pool/with-pool [conn (-> sys :db-pool :pool)]
            (if-let* [user (user/get-user-by-email conn email)
                      user-id (:user-id user)]
              ;; TODO user exists, but in a different org, need a 2nd org invite...
              (if (or (nil? (-> req :params :user-id)) (= (-> req :params :user-id) user-id))
                (if (= (:status user) "pending")
                  (do (timbre/info "Re-inviting user" email) 
                    (invite-response user)) ; TODO send invite
                  (do (timbre/warn "Can't re-invite user" email "in status" (:status user))
                      (ring/error-response "User not eligible for reinvite" 409)))
                (do (timbre/warn "Re-invite request didn't match user" user-id)
                    (ring/error-response "" 404)))
              (do (timbre/info "Creating user" email)
                (if-let [user (email/create-user! conn
                              (email/->user {:email email
                                             :org-id org-id
                                             :one-time-secret (str (java.util.UUID/randomUUID))}
                                            "pending"
                                            (str (java.util.UUID/randomUUID))))] ; random passwd
                  (do (timbre/info "Inviting user" email) 
                      (invite-response user)) ; TODO send invite
                  (do (timbre/error "Failed to create user" email)
                      (ring/error-response "" 500)))))))
        (do (timbre/warn "Invalid request body")
            (ring/error-response "Invalid request body." 400))) ; request not valid
      (do (timbre/warn "Could not parse request body")
          (ring/error-response "Could not parse request body." 400))) ; request not well formed
    (do
      (timbre/warn "Bad token for request")      
      (ring/error-response "Could note confirm token." 401))))

(defn- email-auth [sys req auth-data]
  (if-let* [email (:username auth-data)
            password (:password auth-data)]
    (pool/with-pool [conn (-> sys :db-pool :pool)] 
      (if (email/authenticate? conn email password)
        (do 
          (timbre/info "Authed:" email)
          email)
        (do
          (timbre/info "Failed to auth:" email) 
          false)))))

;; ----- Request Routing -----

(defn- auth-routes [sys]
  (compojure/routes

    ;; Auth API
    
    ;; HATEOAS entry-point
    (GET "/" req (auth-settings req))

    ;; Slack
    (GET "/slack/auth" {params :params} (oauth-callback slack/oauth-callback params)) ; Slack authentication callback
    (GET "/slack/refresh-token" req (refresh-slack-token req)) ; refresh JWToken
    
    ;; Email
    (GET "/email/auth" req (email-auth-response sys req)) ; authentication request
    (GET "/email/refresh-token" req (refresh-email-token sys req)) ; refresh JWToken
    (POST "/email/users" req (email-user-create sys req)) ; new user creation
    
    ;; User Management
    (GET "/org/:org-id/users" req (email-user-enumerate sys req)) ; user enumeration
    (POST "/org/:org-id/users/invite" req (email-user-invite sys req)) ; new user invite
    (GET "/org/:org-id/users/:user-id" req nil) ; user retrieval
    (DELETE "/org/:org-id/users/:user-id" req (user-delete sys req email/prefix)) ; user/invite removal
    (POST "/org/:org-id/users/:user-id/invite" req (email-user-invite sys req)) ; Re-invite
    
    ;; Utilities
    (GET "/ping" [] test-response) ; Up-time monitor
    (GET "/test-token" [] (jwt-debug-response test-token)))) ; JWToken decoding

;; ----- System Startup -----

;; Ring app definition
(defn app [sys]
  (cond-> (auth-routes sys)
    true          wrap-params
    true          (wrap-cors #".*")
    true          (wrap-authentication (backends/basic {:realm "oc-auth"
                                                        :authfn (partial email-auth sys)}))
    c/hot-reload  wrap-reload
    c/dsn         (sentry-mw/wrap-sentry c/dsn)))

;; Start components in production (nginx-clojure)
(when c/prod?
  (timbre/merge-config!
   {:appenders {:spit (appenders/spit-appender {:fname "/tmp/oc-auth.log"})}})
  (timbre/info "Starting production system without HTTP server")
  (def handler
    (-> (components/auth-system {:handler-fn app})
        (dissoc :server)
        component/start
        (get-in [:handler :handler])))
  (timbre/info "Started"))

(defn start
  "Start a development server"
  [port]

  (-> {:handler-fn app :port port}
    components/auth-system
    component/start)

  (println (str "\n" (slurp (io/resource "ascii_art.txt")) "\n"
                "OpenCompany Auth Server\n\n"
                "Running on port: " port "\n"
                "Database: " c/db-name "\n"
                "Database pool: " c/db-pool-size "\n"
                "Hot-reload: " c/hot-reload "\n"
                "Sentry: " c/dsn "\n\n"
                "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start c/auth-server-port))