(ns open-company-auth.test
  "Namespace of data fixtures for use in tests."
  (:require [midje.sweet :refer :all]
            [open-company-auth.lib.utils :as test-utils]
            [cheshire.core :as json]))

(facts "Test endpoints"
  (fact "hit /"
    (let [resp (test-utils/api-request :get "/" {})]
      (:status resp) => 200))
  (fact "hit /auth-settings"
    (let [resp (test-utils/api-request :get "/auth-settings" {})
          body (json/parse-string (:body resp))]
      (:status resp) => 200
      (test-utils/response-mime-type resp) => "application/json"
      (contains? body "auth-url") => true
      (> (count (body "auth-url")) 0) => true
      (contains? body "scope") => true))
  (fact "hit /slack-oauth"
    (let [resp (test-utils/api-request :get "/slack-oauth?code=test&test=true" {})
          body (json/parse-string (:body resp))]
      (:status resp) => 200))
  (fact "hit /test-token"
    (let [resp (test-utils/api-request :get "/test-token" {})
          body (json/parse-string (:body resp))]
      (:status resp) => 200
      (contains? body "jwt-token") => true
      (contains? body "jwt-verified") => true
      (contains? body "jwt-decoded") => true
      (body "jwt-verified") => true)))