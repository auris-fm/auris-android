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
    depth = 0
    block_nests = (nest == 1)
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
        q[1] = "`";  spans[1] = 1; prose[1] = 0
        q[2] = "\""; spans[2] = 0; prose[2] = 0
        q[3] = "'";  spans[3] = 0; prose[3] = 0
    } else {
        n_quote = 2
        q[1] = "\""; spans[1] = 0; prose[1] = 0
        q[2] = "'";  spans[2] = 0; prose[2] = 0
    }
    if (lang == "hash" && triple == 0 && yaml == 0) {
        # Shell quotes continue after a backslash; Python's single quotes do not.
        if (shell == 1) { spans[1] = 1; spans[2] = 1 }
    }
    if (yaml == 1) {
        # **YAML has no quote handling at all.** An apostrophe in an unquoted value -- "Don't
        # merge yet" -- is an ordinary character, not an opener, and treating it as one hid every
        # comment for the rest of the file. A quoted scalar is a value anyway, so there is nothing
        # to collect from one.
        n_quote = 0
    }
    if (triple == 1) {
        # A triple-quoted string that spans lines. Python spells it both ways; Kotlin, C++ and
        # Swift only with the double quote. Getting this wrong is not harmless: a delimiter the
        # language does not have opens a string on text that is really code.
        if (lang == "hash") {
            n_quote = 4
            q[1] = "\"\"\""; spans[1] = 1; prose[1] = 1
            q[2] = "'''";  spans[2] = 1; prose[2] = 1
            q[3] = "\"";   spans[3] = 0; prose[3] = 0
            q[4] = "'";    spans[4] = 0; prose[4] = 0
        } else {
            n_quote = 3
            q[1] = "\"\"\""; spans[1] = 1; prose[1] = 0
            q[2] = "\"";   spans[2] = 0; prose[2] = 0
            q[3] = "'";    spans[3] = 0; prose[3] = 0
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
            # **Kotlin nests block comments, so the walk tracks a depth, not a flag.** In
            # `/* outer /* inner */ still comment */` the inner close does not end the outer
            # comment. Closing at the first `*/` ended it there and left the rest of the outer
            # comment to be read as code, which is how a citation in it went unseen.
            #
            # The text read here IS comment text, including when the block opened and closed on
            # this very line -- a one-line doc comment is the common case, and forgetting to set
            # has_comment made every `/** ... */` on a single line invisible.
            has_comment = 1
            rest = substr(line, p)
            open_idx = block_nests ? index(rest, block_open) : 0
            close_idx = index(rest, block_close)
            # A nested opener that comes before the next closer deepens the comment.
            if (open_idx != 0 && (close_idx == 0 || open_idx < close_idx)) {
                depth++
                comment_text = comment_text " " substr(rest, 1, open_idx - 1)
                p += open_idx + length(block_open) - 2
                continue
            }
            if (close_idx == 0) { comment_text = comment_text " " rest; break }
            comment_text = comment_text " " substr(rest, 1, close_idx - 1)
            depth--
            if (depth <= 0) { in_block = 0; depth = 0 }
            p += close_idx + length(block_close) - 2
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

        # **The longest matching delimiter wins, whatever order the row lists them in.** Matching
        # in table order meant a row listing a single quote before its triple never reached the
        # triple: the single quote claimed the first character and a raw string was parsed as an
        # empty string followed by junk. A row could be correct in every field and wrong from
        # ordering alone.
        best_q = 0
        for (qi = 1; qi <= n_quote; qi++) {
            if (substr(line, p, length(q[qi])) == q[qi] && length(q[qi]) > best_q) {
                best_q = length(q[qi])
                best_index = qi
            }
        }
        if (best_q > 0) {
            quote = q[best_index]
            quote_index = best_index
            # **A string whose contents are prose is collected like a comment's.** Python's
            # docstrings are prose because rationale and design notes live there -- the same kind
            # of writing as a comment, in the same places a reference is most likely to survive a
            # removal elsewhere. Nothing else qualifies: a JavaScript string is a value.
            if (prose[quote_index] == 1) {
                has_comment = 1
                rest = substr(line, p + best_q)
                close_idx = index(rest, quote)
                comment_text = comment_text " " (close_idx == 0 ? rest : substr(rest, 1, close_idx - 1))
                if (close_idx == 0) { p = n; continue }
                p += best_q + close_idx + best_q - 2
                quote = ""
                continue
            }
            p += best_q - 1
            continue
        }

        # Comment markers are tested BEFORE the regex heuristic. A slash prefixes both comment
        # forms, so a regex test first would swallow a real comment.
        if (has_block && substr(line, p, 2) == block_open) {
            in_block = 1
            depth = 1
            p += length(block_open) - 1
            continue
        }

        marker_here = 0
        for (mi = 1; mi <= n_line; mi++) {
            if (substr(line, p, length(marker[mi])) == marker[mi]) {
                # **The word-start test applies to `#` alone.** `#` needs it because `x#y` is a
                # colour, an anchor or a fixture string. `//` and `/*` do not, being unambiguous
                # punctuation, and requiring it lost a comment butted straight against code.
                #
                # The test is the character immediately before the marker, not a running flag:
                # a flag survives whitespace, so `foo  # note` and an indented comment under a
                # line ending in a name were both dropped.
                #
                # Only some `#` languages need the break. In shell `a#b` is a word; in Python and
                # YAML `x#y` IS a comment, because outside a string neither has a notion of a
                # colour or a fragment.
                if (marker[mi] != "#" || hash_needs_break == 0 || p == 1 ||
                    substr(line, p - 1, 1) !~ /[A-Za-z0-9_$]/) {
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

    # **No exemption at all.** An earlier version exempted a lane followed by a colon or a
    # slash, on the grounds that a framework resource looks like a citation. But the tree has no
    # resource attribute inside a comment: every `@android:` occurrence is an XML attribute. The
    # exemption therefore covered a collision the guard never sees, while letting through the most
    # common citation there is, "as @lane: said". A resource attribute written in a comment IS a
    # finding. Do not add the exemption back.
    #
    # The owner of each remaining mention is judged on its own, so one match cannot suppress the
    # rest of the line.

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
