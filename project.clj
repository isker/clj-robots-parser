(defproject clj-robots-parser "0.1.0-SNAPSHOT"
  :description "A Clojure(-script), Google-compliant robots.txt parser"
  :url "https://github.com/isker/clj-robots-parser"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [instaparse "1.4.9"]
                 [lambdaisland/uri "1.1.0"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.11"]]

  :profiles {:dev
             {:dependencies [[org.clojure/clojurescript "1.10.439"]
                             [lein-doo "0.1.11"]]}}

  :aliases {"test-all" ["do"
                        ["test"]
                        ["doo" "nashorn" "test" "once"]]}

  :doo {:build "test"}

  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "target/testable.js"
                                   :output-dir "target"
                                   :main clj-robots-parser.test-runner
                                   ;; Needed for testing with nashorn - none is insufficient
                                   :optimizations :whitespace}}]})
