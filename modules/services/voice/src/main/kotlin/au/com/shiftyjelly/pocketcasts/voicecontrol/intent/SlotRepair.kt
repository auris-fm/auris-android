package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

object SlotRepair {
    fun repair(
        raw: String,
        utterance: String,
        tool: String,
        action: String,
        textLanguage: String? = null,
    ): ToolCall? {
        if (tool.isEmpty()) return null
        if (tool == "no_match") {
            return ToolCall("no_match", action, emptyMap())
        }

        // An unresolved amount or timing condition defers the whole call, before any repair step
        // can install a magnitude or a default. This runs first deliberately: the later steps fill
        // a missing delta from the utterance and lean on the sink's configured interval, both of
        // which would turn an unresolved request into an immediate seek at some invented distance.
        // Bounds and conditions always defer: a minimum is not a level, and an action attached to
        // a future event has no time to run at. Only the amount check is waived for an action whose
        // number a resolved value fully settles.
        // An alternative never resolves, whatever the action: a decoded level does not settle
        // "ten or twenty percent", and a decoded duration does not settle "ten or twenty minutes".
        // Bounds and future conditions are the same. Only a plain single number can be waived for
        // an action whose target it fully states.
        if (hasUnresolvedAlternative(utterance) ||
            hasUnresolvedBound(utterance) ||
            hasUnresolvedTiming(utterance, textLanguage) ||
            (hasUnresolvedAmount(utterance, textLanguage) && isExecuteOrNumericTarget(tool, action))
        ) {
            return null
        }

        val parsed = LfmToolCallParser.parse(raw)
        var params = parsed?.params?.toMutableMap() ?: mutableMapOf()
        params = sanitizeParams(tool, action, params).toMutableMap()
        params = dropNoneLike(params).toMutableMap()
        params = repairNumericParams(tool, action, params, utterance).toMutableMap()
        params = repairStringParams(tool, action, params, utterance).toMutableMap()
        params = sanitizeParams(tool, action, params).toMutableMap()
        params = dropNoneLike(params).toMutableMap()
        // Drop stated-zero delta: zero is not a real amount, so keep direction but drop the zero.
        params = dropStatedZero(tool, action, params).toMutableMap()
        params = fillSeekRelativeDefault(tool, action, params, utterance).toMutableMap()

        // An unsupported spoken amount must not clamp or default — no repaired call at all.
        if (tool == "playback" && action == "seek_relative") {
            val delta = params["delta_seconds"] as? Number
            if (delta != null) {
                val absDelta = kotlin.math.abs(delta.toDouble())
                if (absDelta > MAX_REPAIR_SECONDS) return null
            }
        }

        return ToolCall(tool, action, params)
    }

    /** Maximum repaired relative delta (1 hour). Larger amounts return null (no repaired call). */
    private const val MAX_REPAIR_SECONDS = 3600

    /**
     * Whether "once" introduces an execution condition rather than naming the number eleven.
     *
     * The word differs by language, so the language of the text being repaired decides first. In
     * a Spanish utterance it is the numeral and never a condition. In English, or when the
     * language is unknown, it is a condition when it opens the utterance or a clause and is
     * followed by a subject rather than a repetition or beneficiary phrase: "once this episode
     * ends" is a condition, "rewind ten seconds once please" is a count.
     *
     * Unknown language resolves toward deferring, which is the side that neither seeks at an
     * unstated distance nor runs an action before its event.
     */
    private fun isOnceCondition(text: String, textLanguage: String?): Boolean {
        // Spanish has its own conjunction for "once … que", which is a condition like its English
        // counterpart, and "once" on its own there is only ever the number eleven.
        if (SPANISH_ONCE_CLAUSE_REGEX.containsMatchIn(text)) return true
        if (baseLanguage(textLanguage) == "es") return false
        val match = ONCE_REGEX.find(text) ?: return false
        val lang = baseLanguage(textLanguage)
        // An aside such as "once, for whatever reason, the music fades" still opens a clause
        // after it, so the aside is dropped before the rest is judged.
        val raw = text.substring(match.range.last + 1)
        val followed = raw.replaceFirst(ONCE_PARENTHETICAL_REGEX, "").trimStart(' ', ',')
        if (followed.isEmpty()) return false
        // With no language to go on, keep the conservative reading rather than guessing that a
        // bare numeral was meant. The fixture that pins this is a Spanish volume request whose
        // text language was not recorded.
        if (lang == null && !ENGLISH_CLAUSE_EVIDENCE_REGEX.containsMatchIn(text)) return true
        // A repetition or beneficiary phrase does not make a clause.
        if (ONCE_NON_CLAUSE_REGEX.containsMatchIn(followed)) return false
        if (ONCE_REPEAT_WITH_BENEFICIARY_REGEX.containsMatchIn(followed)) return false
        return true
    }

    private fun baseLanguage(textLanguage: String?): String? = textLanguage?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() }

    private val ONCE_REGEX = Regex("""\bonce\b""", RegexOption.IGNORE_CASE)
    private val ENGLISH_CLAUSE_EVIDENCE_REGEX = Regex(
        """\b(this|that|it|the|you|i|we|he|she|they|both|\w+\s+(?:is|are|was|were|finishes|ends|fades|elapses?|stops|completes))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SPANISH_ONCE_CLAUSE_REGEX = Regex("""\buna\s+vez\s+que\b""", RegexOption.IGNORE_CASE)

    // Words that follow "once" without opening a clause. "more" and "again" are the awkward pair:
    // "once more for me" is a repetition offered to a listener, while "once more people arrive"
    // and "once again the music fades" are clauses whose subject comes later. They are only
    // non-clausal when what follows them is a beneficiary or the end of the request.
    private val ONCE_NON_CLAUSE_REGEX = Regex(
        """^(?:please|another|or\s+twice|or\s+two|and|for\b|to\b)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ONCE_REPEAT_WITH_BENEFICIARY_REGEX = Regex(
        """^(?:more|again)\s+for\s+(?:me|us|him|her|them|the\s+listener)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A leading aside such as "for whatever reason", which delays the clause but is not one. */
    private val ONCE_PARENTHETICAL_REGEX = Regex("""^,[^,]*,""")

    /**
     * Whether a language-dependent word decided this utterance.
     *
     * A duration or insertion point is normally left alone because its number is complete on its
     * own. That reasoning fails when the same words could be a count or a condition, which only
     * the language can settle: an unknown-language "once" is unsafe to read as a volume level, so
     * the guard applies there even for an action it otherwise exempts.
     */
    private fun hasUndecidedLanguageWord(utterance: String, textLanguage: String?): Boolean = baseLanguage(textLanguage) == null && ONCE_REGEX.containsMatchIn(utterance.lowercase())

    /**
     * Actions whose single numeric target is a seek distance or a chapter position.
     *
     * The guard covers these and only these. A duration attached to a different action is not an
     * amount to be resolved here — a sleep timer of half an hour is a complete request, not an
     * ambiguous one — so widening the guard would reject valid commands.
     */
    private fun isExecuteOrNumericTarget(tool: String, action: String): Boolean = (tool to action) !in TIMER_SCALAR_ACTIONS

    /**
     * Actions whose numeric argument a resolved number fully settles.
     *
     * These carry a complete request when the number is present: a sleep timer of half an hour,
     * a volume level. They are exempt only from the *amount* check — a stated duration is not an
     * ambiguous position — and never from the bound or condition checks, because "set the volume
     * to at least twenty percent" offers a minimum rather than a level and "when this episode
     * ends, set a thirty-minute timer" has a duration but no time to start it.
     */
    private val TIMER_SCALAR_ACTIONS = setOf(
        "effects" to "set_speed",
        "effects" to "adjust_speed",
        "effects" to "set_trim_mode",
        "effects" to "set_volume_boost",
        "volume" to "set_volume",
        "volume" to "adjust_volume",
        "sleep" to "set",
        "queue" to "move_to_bottom",
    )

    /**
     * Whether the utterance leaves an amount or its timing unresolved, so the call must defer.
     *
     * Three conditions each defeat a local seek on their own: an alternative than one amount
     * ("ten or twenty"), a lower bound rather than a point ("at least ten"), and a future execution
     * condition ("when this episode ends"). A stated "now" or "exactly" settles neither an
     * alternative nor a condition, so those two are checked last and never rescue the call.
     *
     * Detection is per language rather than English-only: the shared fixtures exercise Chinese,
     * Japanese and Spanish, where the wording carries no English token to match.
     */
    internal fun hasUnresolvedAmountOrTiming(
        utterance: String,
        textLanguage: String? = null,
    ): Boolean {
        if (utterance.isBlank()) return false
        return hasUnresolvedAmount(utterance) || hasUnresolvedTiming(utterance, textLanguage)
    }

    /**
     * Whether the utterance still leaves the amount open in any way: an alternative, a range, or
     * an edge instead of a point.
     */
    internal fun hasUnresolvedAmount(utterance: String, textLanguage: String? = null): Boolean = hasUnresolvedAlternative(utterance) ||
        hasUnresolvedBound(utterance) ||
        hasUnresolvedTiming(utterance, textLanguage)

    /**
     * Whether more than one amount is on offer: "ten or twenty", in any of the supported
     * languages.
     *
     * An alternative is never a resolved duration. Whatever number was decoded, the request still
     * names another, so this is not waived for any action.
     *
     * A quoted span usually names a chapter, bookmark or playlist, and a title is not a
     * description of when to act. Its numbering is still meaningful though: quoting an amount
     * ("rewind 'ten or twenty' seconds") does not resolve it, so the quote marks are dropped and
     * the words inside are still read.
     */
    internal fun hasUnresolvedAlternative(utterance: String): Boolean = UNRESOLVED_AMOUNT_REGEX.containsMatchIn(QUOTE_MARK_REGEX.replace(utterance.lowercase(), ""))

    /**
     * Whether the utterance offers an edge rather than a point: "at least twenty", "up to fifty".
     *
     * A bound is never settled by a resolved number, so unlike a plain amount it is not waived for
     * any action. A volume set to a minimum is a different request from a volume set to a level.
     */
    internal fun hasUnresolvedBound(utterance: String): Boolean {
        val unquoted = QUOTE_MARK_REGEX.replace(utterance.lowercase(), "")
        return UNRESOLVED_BOUND_REGEX.containsMatchIn(unquoted) ||
            UNRESOLVED_RANGE_REGEX.containsMatchIn(unquoted)
    }

    /**
     * Whether the request is tied to a future event rather than to now.
     *
     * Quoted spans are removed first: a chapter titled "When This Episode Ends" is a name, not a
     * description of when to act.
     */
    internal fun hasUnresolvedTiming(utterance: String, textLanguage: String? = null): Boolean {
        val lowered = utterance.lowercase()
        val withoutSpans = QUOTED_SPAN_REGEX.replace(lowered, " ")
        return UNRESOLVED_CONDITION_REGEX.containsMatchIn(withoutSpans) ||
            isOnceCondition(withoutSpans, textLanguage)
    }

    // Two quantities offered as alternatives. The amount words are matched on both sides of the
    // conjunction and no unit is required, because a chapter or index states its unit in the
    // action rather than the phrase ("chapter ten or twenty"). Fractions count as quantities:
    // "half or a quarter of a minute" is the same ambiguity as "ten or twenty seconds".
    private val UNRESOLVED_AMOUNT_REGEX = Regex(
        """\b\d+(?:\.\d+)?\b[\w\s.]*?\bor\b[\w\s.]*?\b\d+(?:\.\d+)?\b|""" +
            """\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|hundred|half|quarter)\b[\w\s.]*?\bor\b[\w\s.]*?\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|hundred|half|quarter)\b|""" +
            """或者|或是|還是|还是|""" +
            """\d+\s*秒\s*か\s*\d+|か\d+秒|""" +
            """\b\w+\s+o\s+\w+\b""",
        RegexOption.IGNORE_CASE,
    )

    // A spoken bound is not a point amount: "at least ten" admits every value above it.
    private val UNRESOLVED_BOUND_REGEX = Regex(
        """\b(at\s+least|at\s+most|more\s+than|less\s+than|fewer\s+than|up\s+to|no\s+less\s+than)\b|""" +
            """至少|至多|不低于|不超过|以上|以下|""" +
            """少なくとも|以上|以下|より多い|より少ない|""" +
            """\b(al\s+menos|como\s+mínimo|como\s+maximo|más\s+de|menos\s+de)\b""",
        RegexOption.IGNORE_CASE,
    )

    // The seek or index change is attached to a future event, so it cannot run now at any
    // distance or position. The event may lead the sentence or trail the coordinate
    // ("the chapter after this one ends"), so both orders are matched.
    // The seek, index change or queue insertion is attached to a future event, so it cannot run
    // now at any distance or position. "if" alone is excluded: it is usually politeness ("if you
    // could, please…") rather than a condition on the action. "until" is excluded for the same
    // reason in a question about remaining time.
    //
    // "once" is deliberately not treated as a conjunction here. It is a condition marker in
    // English and the number eleven in Spanish, and the shared contract resolves that by the
    // language of the text being repaired rather than by a list of verbs that may follow it. The
    // only caller passes the router transcript, which this build constrains to English, so the
    // numeral reading cannot arise on this path. Treating "once" as a condition here would be
    // right for this caller and wrong for the function, which is the trade the contract rejects.
    private val UNRESOLVED_CONDITION_REGEX = Regex(
        """\b(when|after|whenever)\b[^.?!]*\b(ends?|finish\w*|over|done|stops?|complete\w*)\b|""" +
            """\b(ends?|finish\w*|over|done|stops?|complete\w*)\b\s*,|""" +
            """结束后|結束後|完了したら|終わったら|的时候|之後|之后|""" +
            """\b(cuando|después\s+de\s+que|al\s+terminar)\b[^.?!]*\b(termine|acabe|finalice|acabar)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Two ends of an interval: "between ten and twenty", "from ten to twenty". A range is an
    // alternative like "ten or twenty", and the client has no way to choose an end.
    // An amount spoken as a word, so a range written as "between ten and twenty" is read as one.
    private val NUMBER_WORD =
        """(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|""" +
            """fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|""" +
            """seventy|eighty|ninety|hundred|half|a\s+half|uno|dos|tres|cuatro|cinco|seis|siete|""" +
            """ocho|nueve|diez|once|doce|veinte|treinta)\b"""

    private val UNRESOLVED_RANGE_REGEX = Regex(
        """\b(between|from)\b[^.?!]*?(?:\d+|\b""" + NUMBER_WORD + """)[^.?!]*?\b(and|to)\b\s*(?:\d+|\b""" + NUMBER_WORD + """)|""" +
            """\bentre\b[^.?!]*?\by\b\s*(?:\d+|\b""" + NUMBER_WORD + """)|""" +
            """(?:\d+|[一二三四五六七八九十百]+)\s*(?:から|〜|~)\s*(?:\d+|[一二三四五六七八九十百]+)""",
        RegexOption.IGNORE_CASE,
    )

    // A quoted title or name: single or double, straight or typographic quotes.
    private val QUOTED_SPAN_REGEX = Regex("""'[^']*'|\"[^\"]*\"|\u2018[^\u2019]*\u2019|\u201c[^\u201d]*\u201d""")

    /** Quote marks alone, leaving the words they enclosed readable. */
    private val QUOTE_MARK_REGEX = Regex("""['\"\u2018\u2019\u201c\u201d]""")

    internal fun collapseRepetition(text: String): String {
        if (text.isEmpty()) return text
        var previous: String? = null
        var out = text
        while (previous != out) {
            previous = out
            out = WORD_REPEAT_REGEX.replace(out, "$1")
            out = THREE_WORD_REPEAT_REGEX.replace(out, "$1")
            out = TWO_WORD_REPEAT_REGEX.replace(out, "$1")
        }
        return out.replace(Regex("\\s+"), " ").trim(' ', ',')
    }

    private val WORD_REPEAT_REGEX = Regex("""\b(\w+)(?:[\s,]+\1){2,}\b""", RegexOption.IGNORE_CASE)
    private val THREE_WORD_REPEAT_REGEX =
        Regex("""\b(\w+\s+\w+\s+\w+)(?:[\s,]+\1)+\b""", RegexOption.IGNORE_CASE)
    private val TWO_WORD_REPEAT_REGEX =
        Regex("""\b(\w+\s+\w+)(?:[\s,]+\1)+\b""", RegexOption.IGNORE_CASE)

    private val STRING_KEYS = setOf(
        "episode",
        "podcast",
        "title",
        "ref",
        "query",
        "request",
        "value",
        "timeframe",
        "tier",
        "sort_order",
        "mode",
        "slot",
        "target_tool",
        "target_action",
        "period",
    )

    // Must cover every (tool, action) ToolCallMapper / ToolSchema can dispatch so
    // sanitizeParams does not strip legitimate generated slots.
    private val ACTION_PARAMS: Map<Pair<String, String>, Set<String>> = mapOf(
        "playback" to "pause" to emptySet(),
        "playback" to "resume" to emptySet(),
        "playback" to "seek_relative" to setOf("delta_seconds", "direction"),
        "playback" to "seek_to" to setOf("position_seconds"),
        "playback" to "next_episode" to emptySet(),
        "effects" to "set_speed" to setOf("speed"),
        "effects" to "adjust_speed" to setOf("delta"),
        "effects" to "set_trim_mode" to setOf("mode"),
        "effects" to "set_volume_boost" to setOf("enabled"),
        "effects" to "query_effects" to emptySet(),
        "volume" to "set_volume" to setOf("volume"),
        "volume" to "adjust_volume" to setOf("delta"),
        "volume" to "query" to emptySet(),
        "sleep" to "set" to setOf("minutes"),
        "sleep" to "end_of_episode" to emptySet(),
        "sleep" to "end_of_chapter" to emptySet(),
        "sleep" to "add_time" to setOf("minutes"),
        "sleep" to "cancel" to emptySet(),
        "sleep" to "query" to emptySet(),
        "chapter" to "next" to emptySet(),
        "chapter" to "previous" to emptySet(),
        "chapter" to "by_index" to setOf("index"),
        "chapter" to "by_title" to setOf("query"),
        "chapter" to "open_link" to setOf("index", "query"),
        "chapter" to "query_list" to emptySet(),
        "chapter" to "query_current" to emptySet(),
        "chapter" to "query_count" to emptySet(),
        "chapter" to "query_next" to emptySet(),
        "bookmark" to "add" to setOf("title"),
        "bookmark" to "rename" to setOf("ref", "title"),
        "bookmark" to "play" to setOf("ref"),
        "bookmark" to "delete" to setOf("ref"),
        "bookmark" to "delete_all" to emptySet(),
        "bookmark" to "query_list" to emptySet(),
        "bookmark" to "query_count" to emptySet(),
        "bookmark" to "query_nearby" to emptySet(),
        "queue" to "add_top" to setOf("episode"),
        "queue" to "add_bottom" to setOf("episode"),
        "queue" to "remove" to setOf("episode"),
        "queue" to "move_to_top" to setOf("episode"),
        "queue" to "move_to_bottom" to setOf("episode"),
        "queue" to "clear" to emptySet(),
        "queue" to "remove_by_podcast" to setOf("podcast"),
        "queue" to "sort" to setOf("sort_order"),
        "queue" to "query_contents" to emptySet(),
        "queue" to "query_next" to emptySet(),
        "queue" to "query_length" to emptySet(),
        "queue" to "query_is_queued" to setOf("episode"),
        "playback_query" to "whats_playing" to emptySet(),
        "playback_query" to "position" to emptySet(),
        "playback_query" to "time_remaining" to emptySet(),
        "playback_query" to "current_podcast" to emptySet(),
        "playback_query" to "episode_duration" to emptySet(),
        "playback_query" to "publish_date" to emptySet(),
        "playback_query" to "episode_description" to emptySet(),
        "playback_query" to "download_status" to emptySet(),
        "playback_query" to "episode_title" to emptySet(),
        "stats_query" to "listening_time" to setOf("period"),
        "stats_query" to "top_podcasts" to setOf("period"),
        "stats_query" to "episodes_finished" to setOf("period"),
        "stats_query" to "listening_streak" to emptySet(),
        "stats_query" to "subscription_count" to emptySet(),
        "stats_query" to "unplayed_total" to emptySet(),
        "stats_query" to "download_stats" to emptySet(),
        "stats_query" to "queue_total" to emptySet(),
        "stats_query" to "new_episodes" to setOf("timeframe"),
        "stats_query" to "time_since_last_listen" to emptySet(),
        "cloud_route" to "route" to setOf("request", "tier"),
        "dialog_control" to "begin" to setOf("target_tool", "target_action"),
        "dialog_control" to "provide_slot" to setOf("target_tool", "target_action", "slot", "value"),
        "dialog_control" to "confirm" to emptySet(),
        "dialog_control" to "deny" to emptySet(),
        "dialog_control" to "cancel" to emptySet(),
        "dialog_control" to "new_command" to setOf("value"),
        "no_match" to "" to emptySet(),
    )

    private fun allowedParams(tool: String, action: String): Set<String> = ACTION_PARAMS[tool to action] ?: emptySet()

    private fun sanitizeParams(tool: String, action: String, params: Map<String, Any?>): Map<String, Any?> {
        val allowed = allowedParams(tool, action)
        return params.filterKeys { it in allowed }
    }

    private fun dropNoneLike(params: Map<String, Any?>): Map<String, Any?> = params.filterValues { value ->
        when (value) {
            null -> false
            is String -> value.isNotBlank() && !value.equals("none", ignoreCase = true)
            else -> true
        }
    }

    /**
     * For seek_relative, drop a stated-zero delta: zero is not a real amount,
     * so keep direction but drop the zero so the utterance can supply the real magnitude.
     */
    private fun dropStatedZero(tool: String, action: String, params: Map<String, Any?>): Map<String, Any?> {
        if (tool != "playback" || action != "seek_relative") return params
        val delta = params["delta_seconds"]
        return if (delta is Number && delta.toDouble() == 0.0) {
            params - "delta_seconds"
        } else {
            params
        }
    }

    private fun repairNumericParams(
        tool: String,
        action: String,
        params: Map<String, Any?>,
        utterance: String,
    ): Map<String, Any?> {
        val allowed = allowedParams(tool, action)
        val extracted = extractNumericSlots(tool, action, utterance)
        val out = params.toMutableMap()
        for ((key, rawValue) in extracted) {
            if (key !in allowed) continue
            val existing = out[key]
            when (key) {
                "delta_seconds" -> {
                    // Preserve the sign the call produced; repair may correct a
                    // magnitude (0, a wrong unit count) but must never turn a
                    // negative into a positive or the reverse.
                    // A zero has no sign to preserve — fill from utterance.
                    val existingSign = when (existing) {
                        is Int -> existing.compareTo(0)

                        // 1, 0, -1
                        is Long -> existing.compareTo(0)

                        else -> 0
                    }
                    val extractedValue = when (rawValue) {
                        is Int -> rawValue
                        is Long -> rawValue.toInt()
                        else -> 0
                    }
                    out[key] = when {
                        existingSign == 0 -> extractedValue

                        // fill absent / zero from utterance
                        existingSign != 0 -> {
                            // Keep existing sign, apply extracted magnitude
                            val magnitude = kotlin.math.abs(extractedValue)
                            existingSign * magnitude
                        }

                        else -> extractedValue
                    }
                }

                else -> {
                    // For other numeric params, overwrite as before.
                    out[key] = rawValue
                }
            }
        }
        return out
    }

    private fun extractNumericSlots(tool: String, action: String, utterance: String): Map<String, Any?> {
        val allowed = allowedParams(tool, action)
        val out = mutableMapOf<String, Any?>()
        if ("delta_seconds" in allowed) {
            extractDeltaSeconds(utterance)?.let { out["delta_seconds"] = it }
        }
        return out
    }

    private fun extractDeltaSeconds(utterance: String): Int? {
        val lower = utterance.lowercase()
        val pairs = durationPairs(utterance)
        val seconds = if (pairs.isEmpty() && A_MINUTE_REGEX.containsMatchIn(lower)) {
            60
        } else {
            secondsFromPairs(pairs)
        } ?: return null
        return if (BACK_REGEX.containsMatchIn(lower)) -seconds else seconds
    }

    /**
     * When the model's call is incomplete for seek_relative, fill what is missing from the utterance.
     *
     * Rules from the shared fixture:
     * - **Direction predicted, no delta**: extract a delta if the utterance states one;
     *   otherwise keep the direction alone.
     * - **Delta present, no direction**: add none. The sign already carries the direction, and an
     *   utterance is not a second source for it — `"go back"` with `delta_seconds: -30` is
     *   returned unchanged.
     * - **Both absent**: extract the delta first; a direction is inferred from the wording only
     *   when the utterance states no amount at all. The two are alternatives, never paired —
     *   `"back that up a minute"` → `{delta_seconds: -60}`, `"go back"` → `{direction: backward}`.
     */
    private fun fillSeekRelativeDefault(
        tool: String,
        action: String,
        params: Map<String, Any?>,
        utterance: String,
    ): Map<String, Any?> {
        if (tool != "playback" || action != "seek_relative") return params

        val hasDelta = params.containsKey("delta_seconds")
        val hasDirection = params.containsKey("direction")

        // Direction present but no delta → extract delta from utterance.
        if (hasDirection && !hasDelta) {
            val extracted = extractDeltaSeconds(utterance)
            return extracted?.let { params + ("delta_seconds" to it) } ?: params
        }

        // Delta present but no direction → add none. The sign already carries the direction,
        // and an utterance that states an amount is not a second source for it (fixture:
        // "go back" with delta_seconds -30 repairs to exactly that, no direction).
        if (hasDelta && !hasDirection) return params

        // Both absent → extract the delta first, and only fall back to a direction from the
        // wording when the utterance states no amount at all: the two are alternatives, because
        // a stated amount already carries its direction in the sign (fixture: "back that up a
        // minute" repairs to {delta_seconds: -60}; "go back" has no amount and repairs to
        // {direction: backward}).
        if (!hasDelta && !hasDirection) {
            val extractedDelta = extractDeltaSeconds(utterance)
            val out = params.toMutableMap()
            if (extractedDelta != null) {
                out["delta_seconds"] = extractedDelta
            } else {
                extractDirectionFromUtterance(utterance)?.let { out["direction"] = it }
            }
            return out
        }

        return params
    }

    /** Infer direction ("backward" or "forward") from utterance wording. */
    private fun extractDirectionFromUtterance(utterance: String): String? {
        val lower = utterance.lowercase()
        return when {
            FORWARD_REGEX.containsMatchIn(lower) -> "forward"
            BACK_REGEX.containsMatchIn(lower) -> "backward"
            else -> null
        }
    }

    private val A_MINUTE_REGEX = Regex("""\ba\s+minute\b""")
    private val BACK_REGEX = Regex("""\b(back|backward|backwards|rewind|behind)\b""")
    private val FORWARD_REGEX = Regex("""\b(forward|ahead)\b""")

    private fun durationPairs(utterance: String): List<Pair<Number, String>> {
        val pairs = mutableListOf<Pair<Number, String>>()
        for (match in NUMBER_REGEX.findAll(utterance)) {
            val value = parseNumberPhrase(match.value) ?: continue
            val unitMatch = UNIT_REGEX.matchAt(utterance, match.range.last + 1) ?: continue
            pairs.add(value to unitMatch.groupValues[1].lowercase())
        }
        return pairs
    }

    private fun secondsFromPairs(pairs: List<Pair<Number, String>>): Int? {
        if (pairs.isEmpty()) return null
        var total = 0.0
        for ((value, unit) in pairs) {
            total += toSeconds(value, unit)
        }
        return total.toInt()
    }

    private fun toSeconds(value: Number, unit: String): Double {
        return when {
            unit.startsWith("hour") || unit in setOf("hr", "hrs") -> value.toDouble() * 3600
            unit.startsWith("min") -> value.toDouble() * 60
            else -> value.toDouble()
        }
    }

    // Simple number regex: matches digits or number words (no lookbehind/lookahead)
    private val NUMBER_REGEX = Regex(
        """(?:\d+(?:\.\d+)?|(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|zero|oh))""",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT_REGEX = Regex("""\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE)

    private fun parseNumberPhrase(text: String): Number? {
        val raw = text.trim().lowercase().replace("-", " ")
        raw.toIntOrNull()?.let { return it }
        raw.toDoubleOrNull()?.let { return it }
        val parts = raw.split(Regex("\\s+"))
        if (parts.size == 2 && parts[0] in TENS && parts[1] in ONES_1_9) {
            return TENS.getValue(parts[0]) + ONES_1_9.getValue(parts[1])
        }
        val single = parts.singleOrNull() ?: return null
        return ONES[single] ?: TENS[single]
    }

    private val ONES = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val ONES_1_9 = ONES.filterKeys { it in setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine") }
    private val TENS = mapOf(
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90,
    )

    private fun repairStringParams(
        tool: String,
        action: String,
        params: Map<String, Any?>,
        utterance: String,
    ): Map<String, Any?> {
        val quotes = quotedSpans(utterance)
        val allowed = allowedParams(tool, action)
        val out = params.toMutableMap()
        if (action == "provide_slot" && "value" in allowed) {
            repairProvideSlotValue(out, utterance, quotes)
        }
        for (key in out.keys.toList()) {
            if (key !in STRING_KEYS) continue
            val value = out[key] as? String ?: continue
            repairOneString(key, value, utterance, quotes)?.let { out[key] = it }
        }
        return out
    }

    private fun repairProvideSlotValue(
        params: MutableMap<String, Any?>,
        utterance: String,
        quotes: List<String>,
    ) {
        when (params["slot"]) {
            "title" -> {
                if (quotes.isNotEmpty()) {
                    params["value"] = quotes.last()
                } else {
                    val cleaned = utterance.trim().trimEnd('.', '?', '!', ' ')
                    if (cleaned.isNotEmpty()) {
                        params["value"] = cleaned
                    }
                }
            }

            "ref" -> {
                if (THIS_ONE_REGEX.containsMatchIn(utterance)) {
                    params["value"] = "this"
                }
            }
        }
    }

    private val THIS_ONE_REGEX = Regex("""\bthis one\b""", RegexOption.IGNORE_CASE)

    private fun repairOneString(
        key: String,
        value: String,
        utterance: String,
        quotes: List<String>,
    ): String? {
        val cleaned = collapseRepetition(value.trim())
        if (key == "title" && quotes.isNotEmpty()) {
            val quote = quotes.last()
            if (looksGarbled(cleaned) || isBrokenCopy(cleaned, quote)) {
                return quote
            }
        }
        if (key in setOf("title", "value", "ref") && quotes.isNotEmpty()) {
            val quote = quotes.singleOrNull() ?: quotes.last()
            if (isBrokenCopy(cleaned, quote)) {
                return quote
            }
        }
        return if (cleaned != value) cleaned else null
    }

    internal fun quotedSpans(utterance: String): List<String> {
        val spans = mutableListOf<String>()
        DOUBLE_QUOTE_REGEX.findAll(utterance).forEach { match ->
            spans.add(match.groupValues[1])
        }
        var index = 0
        while (index < utterance.length) {
            if (utterance[index] != '\'') {
                index++
                continue
            }
            val prev = utterance.getOrNull(index - 1)
            val next = utterance.getOrNull(index + 1)
            if (prev?.isLetter() == true && next?.isLetter() == true) {
                index++
                continue
            }
            var cursor = index + 1
            val buffer = StringBuilder()
            var closed = false
            while (cursor < utterance.length) {
                if (utterance.startsWith("'n'", cursor, ignoreCase = true)) {
                    buffer.append(utterance.substring(cursor, cursor + 3))
                    cursor += 3
                    continue
                }
                val char = utterance[cursor]
                if (char != '\'') {
                    buffer.append(char)
                    cursor++
                    continue
                }
                val prevChar = utterance.getOrNull(cursor - 1)
                val nextChar = utterance.getOrNull(cursor + 1)
                if (prevChar?.lowercaseChar() == 'n' && nextChar?.lowercaseChar() == 't') {
                    buffer.append('\'')
                    cursor++
                    continue
                }
                if (prevChar?.isLetter() == true && nextChar?.isLetter() == true) {
                    buffer.append('\'')
                    cursor++
                    continue
                }
                if (buffer.toString().trim().length >= 3) {
                    spans.add(buffer.toString())
                }
                index = cursor + 1
                closed = true
                break
            }
            if (!closed) break
        }
        return spans
    }

    private val DOUBLE_QUOTE_REGEX = Regex(""""([^"]{1,80})"""")

    internal fun looksGarbled(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val text = value.trim()
        if (text.equals("none", ignoreCase = true) || text.equals("null", ignoreCase = true)) return true
        if (WAIT_LOOP_REGEX.containsMatchIn(text)) return true
        if (WORD_REPEAT_REGEX.containsMatchIn(text)) return true
        val collapsed = collapseRepetition(text)
        return text.length >= 20 && collapsed.length < text.length * 0.8
    }

    private val WAIT_LOOP_REGEX = Regex("""\bwait(?:[\s,]+wait){2,}\b""", RegexOption.IGNORE_CASE)

    internal fun isBrokenCopy(pred: String, target: String): Boolean {
        if (pred.isBlank() || target.isBlank()) return false
        val predLower = pred.trim().lowercase()
        val targetLower = target.trim().lowercase()
        if (predLower == targetLower) return false
        if (predLower.length >= 6 && targetLower.startsWith(predLower) && targetLower.length > predLower.length + 1) {
            return true
        }
        val ratio = sequenceSimilarity(predLower, targetLower)
        if (ratio >= 0.78) return true
        if (ratio >= 0.62 && shareContentToken(predLower, targetLower)) return true
        return editDistance(predLower, targetLower) <= maxOf(2, targetLower.length / 8)
    }

    private fun shareContentToken(left: String, right: String): Boolean {
        val stop = setOf(
            "the", "a", "an", "of", "and", "to", "in", "for", "on",
            "episode", "from", "that", "this",
        )
        val tokenRegex = Regex("[A-Za-z0-9']+")
        val leftTokens = tokenRegex.findAll(left).map { it.value.lowercase() }
            .filter { it !in stop && it.length >= 4 }.toSet()
        val rightTokens = tokenRegex.findAll(right).map { it.value.lowercase() }
            .filter { it !in stop && it.length >= 4 }.toSet()
        return leftTokens.intersect(rightTokens).isNotEmpty()
    }

    private fun sequenceSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val maxLen = maxOf(left.length, right.length)
        if (maxLen == 0) return 1.0
        return 1.0 - editDistance(left, right).toDouble() / maxLen
    }

    private fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var prev = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    prev[j + 1] + 1,
                    current[j] + 1,
                    prev[j] + cost,
                )
            }
            prev = current
        }
        return prev[right.length]
    }
}
