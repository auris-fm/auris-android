# Scan one file for chat-lane references in comments.
#
# Invoked by check-chat-references.sh. Patterns arrive through -v. Kept in its own file so the
# shell and awk quoting rules are not reasoned about at once.
#
# Prints path:line: raw for each offending line; nothing when the file is clean.
#
# **This is one continuous walk over the file, not one comment per line.** State cannot belong
# to a line when it is the point of a line not to end it: a block comment, a Python docstring
# and a template string all span lines. Carrying only block state meant a closing delimiter was
# read as an opening one. Restarting once per line meant the `b` in `/* a */ /* b` stayed open
# and the prose beneath it was read as code. Both are file-level state, so the walk is too.
#
# **Language syntax comes from CHAT_LANG, set by the caller from the file's extension.** An
# earlier version generalised C-style syntax to every language and got two real files wrong: a
# Python docstring containing `/*` started a phantom block that ran to end of file, and a
# JS regex literal holding a quote hid the rest of its line. A language's own delimiters are the
# only thing that can answer "is this a comment", so the caller states them and this file obeys.
#
# CHAT_LANG values, matching core PR 172's table:
#   c      //  line, /* */ block, " ' quotes          (kt, java, kts, cpp, h, swift, mm)
#   js     //  line, /* */ block, " ' ` quotes, regex (ts)
#   go     //  line, /* */ block, " ' ` quotes        (go)
#   hash   #   line, no block,   " ' quotes           (sh, py)
# CHAT_TRIPLE=1 adds ''' and """ as quotes (Python).

BEGIN {
    in_block = 0
    block_open = "/*"
    block_close = "*/"
    quote = ""
    in_regex = 0
    prev_significant = ""

    # Comment markers, per language, as parallel arrays so awk can loop them.
    n_line = 0
    if (lang == "c" || lang == "js" || lang == "go") {
        n_line = 1; marker[1] = "//"
        has_block = 1
    } else {
        has_block = 0          # a `#` language has no block-comment form
        n_line = 1; marker[1] = "#"
    }

    # String delimiters, longest first so a triple quote wins over a single one.
    #
    # **`spans` is the bound on what a misread costs.** Carrying every quote across lines made one
    # misread quote hide every comment for the REST OF THE FILE, where before it hid one line. A
    # single quote in Kotlin ends at the newline, so only the delimiters that really do span lines
    # are carried: Python's triple quotes, the TypeScript and Go backtick, and shell's quotes,
    # which may continue after a backslash. Everything else closes at end of line.
    n_quote = 0
    if (lang == "js" || lang == "go") {
        n_quote = 3
        q[1] = "`";  spans[1] = 1
        q[2] = "\""; spans[2] = 0
        q[3] = "'";  spans[3] = 0
    } else {
        n_quote = 2
        q[1] = "\""; spans[1] = 0
        q[2] = "'";  spans[2] = 0
    }
    if (lang == "hash" && triple == 0) {
        # Shell quotes continue after a backslash; Python's single quotes do not.
        if (shell == 1) { spans[1] = 1; spans[2] = 1 }
    }
    if (triple == 1) {
        # A triple-quoted string that spans lines. Python spells it both ways; Kotlin, C++ and
        # Swift only with the double quote. Getting this wrong is not harmless: a delimiter the
        # language does not have opens a string on text that is really code.
        if (lang == "hash") {
            n_quote = 4
            q[1] = "\"\"\""; spans[1] = 1
            q[2] = "'''";  spans[2] = 1
            q[3] = "\"";   spans[3] = 0
            q[4] = "'";    spans[4] = 0
        } else {
            n_quote = 3
            q[1] = "\"\"\""; spans[1] = 1
            q[2] = "\"";   spans[2] = 0
            q[3] = "'";    spans[3] = 0
        }
    }
}

{
    line = $0
    n = length(line)
    comment_text = ""
    has_comment = 0

    for (p = 1; p <= n; p++) {
        ch = substr(line, p, 1)

        if (in_block) {
            # A block may close and reopen on the same line: "/* a */ /* b". The close is
            # consumed and the walk continues in the same loop, so the reopened block is
            # entered rather than treated as the end of the comment.
            #
            # The text read here IS comment text, including when the block opened and closed on
            # this very line -- a one-line doc comment is the common case, and forgetting to set
            # has_comment made every `/** ... */` on a single line invisible.
            rest = substr(line, p)
            idx = index(rest, block_close)
            has_comment = 1
            if (idx == 0) { comment_text = comment_text " " rest; break }
            comment_text = comment_text " " substr(rest, 1, idx - 1)
            in_block = 0
            p += idx + length(block_close) - 2
            continue
        }

        if (quote != "") {
            if (ch == "\\") { p++; continue }
            if (length(quote) == 1 ? (ch == quote) : (substr(line, p, length(quote)) == quote)) {
                p += length(quote) - 1
                quote = ""
            }
            continue
        }
        if (in_regex) {
            if (ch == "\\") { p++; continue }
            if (ch == "[") {          # a character class may hold an unescaped slash
                p++
                while (p <= n && substr(line, p, 1) != "]") {
                    if (substr(line, p, 1) == "\\") p++
                    p++
                }
                continue
            }
            if (ch == "/") in_regex = 0
            continue
        }

        # A multi-character delimiter is tested first so a triple quote wins.
        matched_longer = 0
        for (qi = 1; qi <= n_quote; qi++) {
            if (length(q[qi]) > 1 && substr(line, p, length(q[qi])) == q[qi]) {
                quote = q[qi]
                quote_index = qi
                p += length(q[qi]) - 1
                matched_longer = 1
                break
            }
        }
        if (matched_longer) continue
        if (is_a_quote(ch)) {
            for (qi = 1; qi <= n_quote; qi++) {
                if (length(q[qi]) == 1 && q[qi] == ch) { quote_index = qi; break }
            }
            quote = ch
            continue
        }

        # Comment markers are tested BEFORE the regex heuristic. A slash prefixes both comment
        # forms, so a regex test first would swallow a real comment.
        if (has_block && substr(line, p, 2) == block_open) {
            in_block = 1
            p += length(block_open) - 1
            continue
        }

        marker_here = 0
        for (mi = 1; mi <= n_line; mi++) {
            if (substr(line, p, length(marker[mi])) == marker[mi]) {
                # Core's rule: the marker is tested at a word start -- the preceding character
                # must be whitespace, or there is none. In a `#` language this is what keeps a
                # colour literal or a URL fragment from reading as a comment.
                if (p == 1 || is_space(substr(line, p - 1, 1))) {
                    marker_here = 1
                    break
                }
            }
        }
        if (marker_here) {
            comment_text = comment_text " " substr(line, p + length(marker[mi]))
            has_comment = 1
            break
        }

        # A slash opens a regex literal only where a value can begin. Bounded on purpose:
        # reading every slash as a regex opener would swallow ordinary division, and guessing
        # wrong costs a slice nobody reads rather than a reference the guard cannot see.
        if (lang == "js" && ch == "/" &&
            (prev_significant == "" || prev_significant ~ /[=(,:;[{!&|?+\-*%~^<>]/)) {
            in_regex = 1
            prev_significant = ""
            continue
        }

        if (ch !~ /[ \t]/) prev_significant = ch
    }

    # A quote that does not span lines ends with the line, whether or not it was closed. This is
    # what keeps one misread quote to its own line instead of hiding the rest of the file.
    if (quote != "" && spans[quote_index] == 0) quote = ""

    if (!has_comment) next
    if (comment_text == "") next

    # **Only @android: is exempt, and only because it is a framework resource reference**
    # (`@android:color/...`, `@android:id/...`) rather than a mention of a lane. Every other lane
    # followed by a colon is a citation -- "as @spec: said" is the most common form there is -- so
    # exempting the whole `@lane:` shape hid it. Each remaining mention is judged on its own.
    gsub(/@android:[A-Za-z0-9_.:\/]*/, "<resource>", comment_text)

    flagged = 0
    if (comment_text ~ lane_ere) flagged = 1
    if (!flagged && comment_text ~ hex_ere) flagged = 1
    if (flagged) printf "%s:%d: %s\n", FILENAME, FNR, line
}

function is_a_quote(c) {
    for (qi = 1; qi <= n_quote; qi++) if (length(q[qi]) == 1 && q[qi] == c) return 1
    return 0
}
function is_space(c) { return c == " " || c == "\t" }
