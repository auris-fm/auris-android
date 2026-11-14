#!/bin/sh
# A hash only starts a comment at a word break in shell: a#b is a word, not a comment.
colour=a#b
echo "a # b inside a string"
