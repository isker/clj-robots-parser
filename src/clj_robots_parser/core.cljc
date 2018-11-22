(ns clj-robots-parser.core
  (:require [instaparse.core :as insta]
            [lambdaisland.uri :as uri]
            [clojure.string :as str]
            #?(:cljs [goog.string :a gstring])))


;; Adapted from https://developers.google.com/search/reference/robots_txt.
;;
;; There are some notable changes:
;; * Parse lines, not the entire document.  The whole-document grammar is
;;   ambiguous, which instaparse allows but I don't like.  We chunk the
;;   document into lines before using this grammar.
;; * This is substantially less stringent on the URL format as I'm not
;;   going to bother inlining multiple RFCs here.
(insta/defparser robots-txt-line

  "
robotsline = useragent | allow | disallow | sitemap | otherline | comment

useragent = <[ws]> <'user-agent'> <[ws]> <':'> <[ws]> agentvalue <[comment]>
allow =     <[ws]> <'allow'>      <[ws]> <':'> <[ws]> pathvalue
disallow =  <[ws]> <'disallow'>   <[ws]> <':'> <[ws]> pathvalue
sitemap =   <[ws]> <'sitemap'>    <[ws]> <':'> <[ws]> textvalue

otherline = !'user-agent' !'allow' !'disallow' !'sitemap'
            <[ws]> keyvalue       <[ws]> <':'> <[ws]> textvalue [comment]

comment = <[ws]> '#' anyvalue

pathvalue = '/' textvalue*
<keyvalue>   = #'[^\\x00-\\x1F\\x7F\r\n\t #:]+'
<textvalue>  = #'[^\\x00-\\x1F\\x7F\r\n\t #]+'
agentvalue   = #'[^\\x00-\\x1F\\x7F\r\n\t#]+'
<anyvalue>   = #'[^\\x00-\\x1F\\x7F\r\n]*'

<ws> = #'[ \t]+'
  "
  :string-ci true)

(defn- parse-line
  "Parses the given line, returning nil if it is not valid according to
  the grammar."
  [line-string]
  (let [result (robots-txt-line line-string)]
    (if (insta/failure? result)
      nil
      (insta/transform {:pathvalue str :agentvalue (comp str/trim str/lower-case)} result))))

(defn- categorize-line
  [parsed-line]
  (case (first parsed-line)
    :useragent :group
    :allow     :group
    :disallow  :group
    :sitemap   :sitemap
    :ignored))

(defn- categorize-lines
  [parsed-lines]
  (->> parsed-lines
       (map second)
       (group-by categorize-line)))

(defn- comparator-by
  [f]
  (fn [k1 k2]
    (compare (f k1) (f k2))))

(defn- update-group-map
  [directives d]
  (if directives
    (conj directives d)
    ;; Longest directive paths first
    (sorted-set-by (comparator-by #(- (count (second %)))) d)))

(defn- by-user-agent
  "Transforms a seq of 'group' lines into a map of user-agent ->
  directives according to
  https://developers.google.com/search/reference/robots_txt#grouping-of-records.

  Both the map of user agents and each list of directives are sorted
  by length (longest first).  This is to aid evaluating the rules
  later."
  [lines]
  ;; Stateful loop goop :(.  This is a very stateful reduction.
  ;;
  ;; We need to track the preceding (potentially multiple consecutive!)
  ;; User-Agent line(s) and associate all following (dis)allow directives under
  ;; each of them.
  (loop [lines lines
         encountering-agents false
         current-agents #{}
         ;; Longest UAs first
         result (sorted-map-by (comparator-by #(- (count %))))]
    (let [line (first lines)
          [type _] line]
      (cond
        (nil? line) result

        ;; Add this to the set of current agents if the last line was also an
        ;; agent, otherwise make this the sole current agent
        (= type :useragent)
        (recur (rest lines)
               true
               (if encountering-agents
                 (conj current-agents (second line))
                 #{(second line)})
               result)

        ;; Stop encountering agents and place this directive into the set of
        ;; directives of all current agents
        (contains? #{:allow :disallow} type)
        (recur (rest lines)
               false
               current-agents
               (if (not-empty current-agents)
                 (reduce #(update %1 %2 update-group-map line)
                         result
                         current-agents)
                 ;; no preceding UA -> discard
                 result))))))

(defn parse
  "Parses the given string (content of a robots.txt file) into data that
  can be queried."
  [content]
  (let [parsed (->> content
                    (str/split-lines)
                    (map parse-line)
                    (filter some?))
        {:keys [group sitemap]} (categorize-lines parsed)]
    {:sitemap-urls (into [] (map second sitemap))
     :agent-rules (by-user-agent group)}))

(defn- re-quote
  [s]
  #?(:clj  (java.util.regex.Pattern/quote s)
     :cljs (gstring/regExpEscape s)))

(defn- regexify
  "Turns strings that may contain robots.txt-style wildcards (*, $) into
  regexes."
  [path]
  (let [end-anchor (if (str/ends-with? path "$") "$" "")
        p (if (empty? end-anchor)
            path
            (subs path 0 (- (count path) 1)))]
    (as-> p p
      (str/split p #"\*+")
      (map re-quote p)
      (str/join ".*" p)
      (str p end-anchor)
      (re-pattern p))))

(def regex-memo (memoize regexify))

(defn- rule-verdict
  [[rule rule-path] test-path]
  (if (re-find (regex-memo rule-path) test-path)
    ;; return the type of the rule - :allow or :disallow
    rule
    ;; we have nothing to say
    nil))

(defn is-crawlable?
  "Is the given user-agent allowed to access the given URL by the given
  parsed robots.txt?"
  [url user-agent {:keys [agent-rules]}]
  (let [path (:path (uri/parse url))
        user-agent (str/lower-case user-agent)
        rules (or (some->> agent-rules
                           (filter #(str/includes? user-agent (key %)))
                           (first)
                           (val))
                  (some->> agent-rules
                           (filter #(= "*" (key %)))
                           (first)
                           (val))
                  ([:allow "/"]))]
    (case (some #(rule-verdict % path) rules)
      :allow true
      :disallow false
      ;; robots.txt had nothing to say about this URL - crawl away
      nil true)))
