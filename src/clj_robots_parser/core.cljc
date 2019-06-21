(ns clj-robots-parser.core
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]
            #?(:clj [instaparse.core :as insta :refer [defparser]]
               :cljs [instaparse.core :as insta :refer-macros [defparser]])
            #?(:cljs [goog.string :as gstring])))


;; Adapted from https://developers.google.com/search/reference/robots_txt.
;;
;; There are some notable changes:
;; * Parse lines, not the entire document.  The whole-document grammar is
;;   ambiguous, which instaparse allows but I don't like.  We chunk the
;;   document into lines before using this grammar.
;; * This is substantially less stringent on the URL format as I'm not
;;   going to bother inlining multiple RFCs here.
(defparser robots-txt-line

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
  [line-number line]
  (let [result (robots-txt-line line)]
    (when-not (insta/failure? result)
      (with-meta
        (insta/transform {:robotsline identity ; strips the :robotsline key
                          :pathvalue str
                          :agentvalue (comp str/trim str/lower-case)} result)
        {:line-number line-number}))))

(defn- categorize-line
  [parsed-line]
  (case (:type parsed-line)
    :useragent :group
    :allow     :group
    :disallow  :group
    :sitemap   :sitemap
    :ignored))

(defn- categorize-lines
  [parsed-lines]
  (group-by categorize-line parsed-lines))

(defn- longest-strings-first
  "Extracts strings with given function.  Sorts strings by length,
  longest coming first.  Breaks ties with regular string comparison."
  ([s1 s2]
   (compare [(count s2) s1]
            [(count s1) s2]))
  ([f]
   (fn [x y]
     (let [s1 (f x)
           s2 (f y)]
       (longest-strings-first s1 s2)))))

(defn- update-agent-value
  [agent-value directive agent-line-number]
  (if agent-value
    (update-in agent-value [:directives] conj directive)
    {:line-number agent-line-number
     ;; Longest directive paths first
     :directives (sorted-set-by (longest-strings-first :value) directive)}))

(defn- by-user-agent
  "Transforms a seq of 'group' lines into a map of user-agent ->
  directives according to
  https://developers.google.com/search/reference/robots_txt#grouping-of-records.

  Both the map of user agents and each list of directives are sorted
  by length (longest first).  This is to aid evaluating the directives
  later."
  [lines]
  ;; Stateful loop goop :(.  This is a very stateful reduction.
  ;;
  ;; We need to track the preceding (potentially multiple consecutive!)
  ;; User-Agent line(s) and associate all following (dis)allow directives under
  ;; each of them.
  (loop [lines lines
         encountering-agents false
         current-agents {}
         ;; Longest UAs first
         result (sorted-map-by longest-strings-first)]
    (let [line (first lines)
          type (:type line)]
      (cond
        (nil? line) result

        ;; Add this to the set of current agents if the last line was also an
        ;; agent, otherwise make this the sole current agent
        (= type :useragent)
        (recur (rest lines)
               true
               (if encountering-agents
                 (assoc current-agents (:value line) (:line-number line))
                 {(:value line) (:line-number line)})
               result)

        ;; Stop encountering agents and place this directive into the set of
        ;; directives of all current agents
        (contains? #{:allow :disallow} type)
        (recur (rest lines)
               false
               current-agents
               (if (not-empty current-agents)
                 ;; For each agent, create a map containing that agent's
                 ;; directives and its line number.
                 (reduce (fn [result [agent line-number]]
                           (update result agent update-agent-value line line-number))
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
                    (map-indexed parse-line)
                    (filter some?)
                    (map (fn [line]
                           {:type (first line)
                            :value (second line)
                            :line-number (:line-number (meta line))})))
        {:keys [group sitemap]} (categorize-lines parsed)]
    {:sitemap-urls sitemap
     :agent-groups (by-user-agent group)
     :raw-content content}))

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
      (str "^" p end-anchor)
      (re-pattern p))))

(def ^:private regex-memo (memoize regexify))

(defn- directive-verdict
  [[user-agent {:keys [directives line-number]}] test-path]
  (let [matching-directive
        (->> directives
             (filter #(re-find (regex-memo (:value %)) test-path))
             first)]
    (if matching-directive
      {:result (:type matching-directive)
       :because {:directive matching-directive
                 :user-agent {:value user-agent
                              :line-number line-number}}}
      ;; we have nothing to say
      {:result :allow
       :because nil})))

(defn query-crawlable
  "Determines whether and explains why the given parsed robots.txt does
  or does not permit the given URL to be crawled by the given
  user-agent."
  [{:keys [agent-groups]} url user-agent]
  (let [path (str (assoc (uri/parse url)
                         ;; Drop anything in the URI before the path.  We do it
                         ;; this way to take advantage of toString on uri/URI.
                         :scheme nil
                         :user nil
                         :password nil
                         :host nil
                         :port nil))
        user-agent (str/lower-case user-agent)
        agent-rule (or (first (filter #(str/includes? user-agent (key %)) agent-groups))
                       ;; Special case: wildcard user agent. Only this exact string
                       ;; is a valid wildcard - UA wildcards are not as general as
                       ;; wildcards in allow/disallow paths.
                       (first (filter #(= "*" (key %)) agent-groups)))]
    (assoc (directive-verdict agent-rule path) :query {:url url :user-agent user-agent})))

(defn is-crawlable?
  "Does the given parsed robots.txt permit the given URL to be crawled
  by the given user-agent?"
  [robots-txt url user-agent]
  (= (:result (query-crawlable robots-txt url user-agent)) :allow))
