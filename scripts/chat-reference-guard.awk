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
        # A block may close and reopen on the same line: "/* a */ /* b". The restart is a
        # LOOP over the remainder, not a single check, or the reopened block is treated as
        # closed and its continuation lines are scanned as code.
        while (match(comment, /\*\//)) {
            rest = substr(comment, RSTART + 2)
            if (match(rest, /\/\*/)) {
                comment = substr(rest, RSTART)
                continue
            }
            comment = substr(comment, 1, RSTART + 1)
            in_block = 0
            break
        }
    } else if (match(code, /\/\*/)) {
        comment = substr(raw, RSTART)
        if (match(substr(code, RSTART + 2), /\*\//)) {
            # Closed on this line. Scan past it: a second opener may follow.
            after = substr(raw, RSTART + 2)
            while (match(after, /\*\//)) {
                after = substr(after, RSTART + 2)
                if (match(after, /\/\*/)) {
                    comment = substr(after, RSTART)
                    in_block = 1
                    break
                }
            }
        } else {
            in_block = 1
        }
    } else if (match(code, /\/\//)) {
        comment = substr(raw, RSTART)
    } else if (match(code, /^[ \t]*#/)) {
        # A leading hash comment (shell, Python, Go).
        comment = substr(raw, RSTART)
    } else if (match(code, /#[ \t]/)) {
        # A trailing hash comment. Strings are blanked in `code` above, so a "#" inside a
        # literal is already gone. Requiring whitespace after the "#" keeps a colour
        # literal or a shell parameter from reading as a comment.
        comment = substr(raw, RSTART + 1)
    } else if (raw ~ /^[ \t]*\*/) {
        # Continuation lines of a KDoc or C-style block comment.
        comment = raw
    }

    if (comment == "") next

    # A framework attribute or a scoped package on the SAME line must not suppress a real
    # reference beside it, so those forms are removed from the text and each remaining
    # mention is judged on its own -- which is what checking each match separately means.
    gsub(/@(spec|reviewer|cloud|trainer|guru|android|ios|cindy|merlinran)[:\/][A-Za-z0-9_.:\/-]*/, "<attr>", comment)

    flagged = 0
    if (comment ~ lane_ere) flagged = 1
    if (!flagged && comment ~ hex_ere) flagged = 1

    if (flagged) printf "%s:%d: %s\n", FILENAME, FNR, raw
}
