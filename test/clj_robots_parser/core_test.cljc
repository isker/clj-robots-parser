(ns clj-robots-parser.core-test
  (:require #?(:clj [clojure.test :refer [deftest is testing are]]
               :cljs [cljs.test :refer-macros [deftest is testing are]])
            [clj-robots-parser.core :refer [parse is-crawlable?]]))

(def robots-simple "
  sitemap: https://example.com/sitemap1
  allow : /no-user-agent-so-we-ignore
  user-Agent: *# how bout that we can comment here yee haw
  some garbo line that should be ignored
  alloW: /Foo
  Allow: /bar
  disallow: /Foobar
  sitemap: https://example.com/sitemap2\r
  disAllow: not a path
  sItemAp: https://example.com/sitemap3 #no comments on URL lines
  \t# comments on their own lines are just fine dawg
  ")

(def multiple-user-agents "
  user-agent: google
  user-agent: *
  disallow: /

  user-agent: GOOGLEbot
  user-agent: msn
  user-agent: big
  allow: /
  disallow: /secret-lair?cat=etcpwd
  ")

(deftest test-parse
  (testing "simple data extraction"
    (let [results (parse robots-simple)]
      (is (= ["https://example.com/sitemap1" "https://example.com/sitemap2"] (:sitemap-urls results)))
      (is (= {"*" #{[:disallow "/Foobar"] [:allow "/Foo"] [:allow "/bar"]}} (:agent-rules results)))))
  (testing "ordering of user agents and directives by length"
    (let [{:keys [agent-rules]} (parse multiple-user-agents)]
      (is (= {"googlebot" #{[:disallow "/secret-lair?cat=etcpwd"] [:allow "/"]}
              "google"    #{[:disallow "/"]}
              "big"       #{[:disallow "/secret-lair?cat=etcpwd"] [:allow "/"]}
              "msn"       #{[:disallow "/secret-lair?cat=etcpwd"] [:allow "/"]}
              "*"         #{[:disallow "/"]}}

             agent-rules)))))

(deftest test-query
  (let [results (parse multiple-user-agents)]
    (are [expect args] (= expect (is-crawlable? results (:url args) (:ua args)))
      true  {:url "/Foobar" :ua "Googlebot 1.0"}
      true  {:url "/Foobar/secret-lair?cat=etcpwd" :ua "Googlebot 1.0"}
      true  {:url "https://www.example.com/Foobar" :ua "googlebot 1.0"}
      true  {:url "/" :ua "Googlebot 1.0"}
      false {:url "/secret-lair?cat=etcpwd#yes" :ua "big boss baws"}
      false {:url "/secret-lair?cat=etcpwd" :ua "msnbawt"}
      true  {:url "/secret-lair" :ua "msnbawt"}
      false {:url "https://user:password@www.example.com:8080/Foobar?bar=baa#baz" :ua "google salt bae"}
      false {:url "/Foobar" :ua "google salt bae"}
      false {:url "/Foobar" :ua "anything"}
      false {:url "/whatever" :ua "anything"})))
