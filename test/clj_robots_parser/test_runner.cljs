(ns clj-robots-parser.test-runner
  (:require [cljs.test :as t :include-macros true]
            [clj-robots-parser.core-test]
            [doo.runner :refer-macros [doo-tests]]))

(doo-tests 'clj-robots-parser.core-test)
