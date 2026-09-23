(ns isaac.google.cli-spec
  "The `isaac google smoke` door URL used to build from the raw :http :port
   config key, which most hosts leave unset — so the probe was unreachable
   while curl on the real port answered 401 (isaac-8zl8). It must instead
   carry the port isaac-http's own resolved bind config would actually use."
  (:require
    [isaac.fs :as fs]
    [isaac.google.cli :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "google cli — default door url (isaac-8zl8)"

  ;; isaac-http's own resolved bind config (isaac.config.server-config/
  ;; server-config) composes the schema from the classpath's module
  ;; manifests, which needs a real filesystem to discover — not the mem-fs
  ;; most specs in this project install for the Google root's own files.
  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "carries isaac-http's own resolved default port when the host sets no :http :port"
    (should= "http://127.0.0.1:6674/google/pubsub"
             (#'sut/default-door-url {})))

  (it "carries the configured port when the host sets one"
    (should= "http://127.0.0.1:9999/google/pubsub"
             (#'sut/default-door-url {:http {:port 9999}})))
  )
