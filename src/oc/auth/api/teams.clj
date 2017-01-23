(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [if-let.core :refer (if-let* when-let*)]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET POST PUT PATCH DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.schema :as lib-schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.user :as user-rep]))

;; ----- Actions -----

(defn teams-for-user
  "Return a sequence of teams that the user, specified by their user-id, is a member of."
  [conn user-id]
  (when-let* [user (user-res/get-user conn user-id)
            teams (:teams user)]
    (team-res/get-teams conn teams [:admins :created-at :updated-at])))

;; ----- Validations -----

(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  :available-charsets [api-common/UTF8]
  :available-media-types [team-rep/collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/collection-media-type)

  :handle-ok (fn [ctx] (let [user-id (-> ctx :user :user-id)]
                        (team-rep/render-team-list (teams-for-user conn user-id) user-id))))

;; A resource for operations on a particular team
(defresource team [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete]

  :available-media-types [team-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/media-type)
  
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  :delete! (fn [_] (team-res/delete-team! conn team-id))

  :handle-ok (fn [ctx] (let [admins (set (-> ctx :existing-team :admins))
                             users (user-res/list-users conn team-id) ; users in the team
                             user-admins (map #(if (admins (:user-id %)) (assoc % :admin true) %) users)
                             user-reps (map #(user-rep/render-user-for-collection team-id %) user-admins)
                             team (assoc (:existing-team ctx) :users user-reps)]
                          (team-rep/render-team team))))

;; A resource for the admins of a particular team
(defresource admin [conn team-id user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]
  :malformed? false

  :available-media-types [team-rep/admin-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/admin-media-type)
  
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

  :put-to-existing? true
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                               user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))
                               member? ((set (:teams user)) (:team-id team))]
                        {:existing-user user :admin? ((set (:admins team)) (:user-id user))}
                        false))

  :put! (fn [ctx] (when (:existing-user ctx)
                    {:updated-team (team-res/add-admin conn team-id user-id)}))
  :delete! (fn [ctx] (when (:admin? ctx)
                      {:updated-team (team-res/remove-admin conn team-id user-id)}))

  :respond-with-entity? false
  :handle-created (fn [ctx] (when-not (:updated-team ctx) (api-common/missing-response)))
  :handle-no-content (fn [ctx] (when-not (:updated-team ctx) (api-common/missing-response))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; team listing
      (OPTIONS "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (OPTIONS "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      ;; team operations
      (OPTIONS "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (GET "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (DELETE "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      ;; team admin operations
      (OPTIONS "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id)))
      (PUT "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id)))
      (DELETE "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id))))))