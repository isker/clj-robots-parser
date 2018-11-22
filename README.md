# clj-robots-parser [![Build Status](https://travis-ci.com/isker/clj-robots-parser.svg?branch=master)](https://travis-ci.com/isker/clj-robots-parser)
## What
A Clojure(-script) library to parse robots.txt files as specified by [The Great
Goog themselves](https://developers.google.com/search/reference/robots_txt).  As
robots.txt is woefully underspecified in the ["official"
docs](http://www.robotstxt.org/), this library tolerates anything it doesn't
understand, extracting the data it does.

It can use the extracted data to query whether a given user-agent is allowed to
crawl a given URL.

## Why
Why use Google's (much more stringent) documentation for handling robots.txt?
In terms of SEO, googlebot is what you ought to care about the most.
