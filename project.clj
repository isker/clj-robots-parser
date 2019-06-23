(defproject isker/clj-robots-parser "0.1.2"
  :description "A Clojure(-script), Google-compliant robots.txt parser"
  :url "https://github.com/isker/clj-robots-parser"
  :license {:name         "MIT License"
            :url          "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [instaparse "1.4.10"]
                 [lambdaisland/uri "1.1.0"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.11"]]

  :profiles {:dev
             {:dependencies [[org.clojure/clojurescript "1.10.520"]
                             [lein-doo "0.1.11"]]}}

  :aliases {"test-all" ["do"
                        ["test"]
                        ["doo" "node" "test" "once"]]}

  :doo {:build "test"}

  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "target/testable.js"
                                   :output-dir "target"
                                   :target :nodejs
                                   :main clj-robots-parser.test-runner}}]})
