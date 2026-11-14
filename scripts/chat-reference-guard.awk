# Scan one file for chat-lane references in comments.
#
# Invoked by check-chat-references.sh with the patterns passed through -v. Kept in its own
# file so the shell and awk quoting rules do not have to be reasoned about at the same time.
#
# Prints path:line: raw for each offending line; prints nothing when the file is clean.
#
# The whole design question here is where a comment STARTS. Code and comments need
# different treatment -- a string literal is not a citation, but comment prose is exactly
# where citations live -- and the two cannot be scanned by the same rule. So each line is
# split first: `code` is the part before the comment marker (used only to locate markers,
# never to match), and `comment` is the prose (the only part that is matched).


BEGIN { in_block = 0 }

{
    raw = $0
    code = raw
    comment = ""

    # Blank string and char literals in CODE only. An apostrophe in English prose
    # ("Reviewer's") must never be read as a char-literal opener, which is what happens if
    # the whole line is scanned for literals before the comment is split off.
    n = length(code); out = ""; in_str = 0; in_char = 0; esc = 0
    for (i = 1; i <= n; i++) {
        c = substr(code, i, 1)
        if (esc)       { esc = 0; out = out " "; continue }
        if (c == "\\") { esc = 1; out = out " "; continue }
        if (in_str)    { if (c == "\"") in_str = 0;  out = out " "; continue }
        if (in_char)   { if (c == "'") in_char = 0;   out = out " "; continue }
        if (c == "\"") { in_str = 1; out = out " "; continue }
        if (c == "'")  { in_char = 1; out = out " "; continue }
        out = out c
    }
    code = out

    if (in_block) {
        comment = raw
        if (index(raw, "*/") > 0) in_block = 0
    } else if (match(code, /\/\*/)) {
        comment = substr(raw, RSTART)
        if (index(substr(code, RSTART + 2), "*/") == 0) in_block = 1
    } else if (match(code, /\/\//)) {
        comment = substr(raw, RSTART)
    } else if (raw ~ /^[ \t]*\*/) {
        # Continuation lines of a KDoc or C-style block comment.
        comment = raw
    } else if (raw ~ /^[ \t]*#/) {
        # Shell, Python and Go comments.
        comment = raw
    }

    if (comment == "") next

    flagged = 0

    if (comment ~ lane_ere) {
        # A lane followed by ':' or '/' is framework resource syntax (@android:color) or a
        # scoped package import (@cloudflare/), not a citation.
        if (comment !~ resource_ere) flagged = 1
    }
    if (!flagged && comment ~ hex_ere) flagged = 1

    if (flagged) printf "%s:%d: %s\n", FILENAME, FNR, raw
}
