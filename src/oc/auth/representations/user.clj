(ns oc.auth.representations.user
  "Resource representations for OpenCompany users."
  (:require [clojure.string :as s]
            [defun.core :refer (defun defun-)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.resources.user :as user-res]))

(def media-type "application/vnd.open-company.user.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.user+json;version=1")

(def representation-props [:user-id :first-name :last-name :email :avatar-url :created-at :updated-at])
(def jwt-props [:user-id :first-name :last-name :name :email :avatar-url :teams])

(defun url
  ([user-id :guard string?] (str "/users/" user-id))
  ([user :guard map?] (url (:user-id user))))

(defn self-link [user-id] (hateoas/self-link (url user-id) media-type))

(defn user-link [user-id] (hateoas/link-map "user" hateoas/GET (url user-id) media-type))

(defn- partial-update-link [user-id] (hateoas/partial-update-link (url user-id) media-type))

(defn- delete-link [user-id] (hateoas/delete-link (url user-id)))

(defn refresh-link [user-id] (hateoas/link-map "refresh" 
                                hateoas/GET
                                (str (url user-id) "/refresh-token")
                                jwt/media-type))

(def teams-link (hateoas/collection-link "/teams" team-rep/collection-media-type))

(defn authed-settings [user-id] {:links [(user-link user-id)
                                         (refresh-link user-id)
                                         teams-link]})

(defn- user-links [user]
  (let [user-id (:user-id user)]
    (assoc user :links [
      (self-link user-id)
      (partial-update-link user-id)
      (refresh-link user-id)
      (delete-link user-id)
      teams-link])))

(defun- name-for 
  ([user] (name-for (:first-name user) (:last-name user)))
  ([first-name :guard s/blank? last-name :guard s/blank?] "")
  ([first-name last-name :guard s/blank?] first-name)
  ([first-name :guard s/blank? last-name] last-name)
  ([first-name last-name] (str first-name " " last-name)))

(defn jwt-props-for [user source]
  (-> (zipmap jwt-props (map user jwt-props))
    (assoc :name (name-for user))
    (assoc :source source)))

(defn auth-response
  "Return a JWToken for the user, or and a Location header."
  [user source location?]
  (let [jwt-user (jwt-props-for user source)
        headers (if location? {"Location" (url (:user-id user))} {})
        status (if location? 201 200)]
    (api-common/text-response (jwt/generate jwt-user config/passphrase) status headers)))

(defn render-user
  "Create a JSON representation of the user for the REST API"
  [user]
  (let [user-id (:user-id user)]
    (json/generate-string
      (-> user
        (select-keys representation-props)
        (assoc :passsword "")
        (user-links))
      {:pretty true})))