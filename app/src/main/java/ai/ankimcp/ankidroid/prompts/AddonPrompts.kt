package ai.ankimcp.ankidroid.prompts

import ai.ankimcp.ankidroid.mcp.Prompt
import ai.ankimcp.ankidroid.mcp.PromptArgument
import ai.ankimcp.ankidroid.mcp.PromptArgumentException
import ai.ankimcp.ankidroid.mcp.Resource

/**
 * Text shipped under `src/main/resources/ai/ankimcp/ankidroid/prompts/`, read once. The path is
 * absolute because R8 renames and repackages this class while the resource keeps its path, so a
 * name relative to the class's package would not be found in a release build (D57).
 */
internal fun bundledText(file: String): String =
    checkNotNull(AddonPrompts::class.java.getResourceAsStream("$BUNDLED_DIR$file")) { "Missing bundled $file" }
        .use { it.readBytes().toString(Charsets.UTF_8) }

internal const val BUNDLED_DIR = "/ai/ankimcp/ankidroid/prompts/"

/** The add-on's prompts and resources: two as static text, review_session adapted (D57). */
object AddonPrompts {
    val prompts: List<Prompt> get() = listOf(ReviewSessionPrompt, TwentyRulesPrompt)
    val resources: List<Resource> get() = listOf(QuerySyntaxResource)
}

/** `twenty_rules`: the add-on's `twenty_rules_content.md`, byte for byte. */
object TwentyRulesPrompt : Prompt {
    override val name = "twenty_rules"
    override val description =
        "Twenty rules of formulating knowledge for effective Anki flashcard creation " +
            "based on Dr. Piotr Wozniak's SuperMemo research"
    override val arguments = emptyList<PromptArgument>()

    private val content by lazy { bundledText("twenty_rules_content.md") }

    override fun render(arguments: Map<String, String>): String = content
}

/** `anki://query-syntax`: the add-on's dict, as FastMCP serialises it (JSON, indent 2). */
object QuerySyntaxResource : Resource {
    override val uri = "anki://query-syntax"
    override val name = "query_syntax"
    override val title = "Search Query Syntax"
    override val description = "Get Anki search query syntax documentation for constructing card/note searches."

    private val content by lazy { bundledText("query_syntax.json") }

    override fun read(): String = content
}

/**
 * `review_session`, adapted from the add-on's (review_session_prompt.py) for the phone (D57):
 * same arguments and structure, but syncing is something the user does in AnkiDroid, a repeated
 * rate_card call is warned against, the voice style has no media skipping or unburying, and
 * there is no "gui" style: it and any other unknown style get the interactive one.
 */
object ReviewSessionPrompt : Prompt {
    override val name = "review_session"
    override val description =
        "Creates a structured prompt for conducting an Anki review session with the AnkiDroid collection. " +
            "Use this prompt to guide the LLM through presenting cards, collecting answers, " +
            "and rating cards appropriately. Helps maintain consistent review workflow. " +
            "review_style is interactive (default), quick or voice."
    override val arguments = listOf(
        PromptArgument("deck_name"),
        PromptArgument("card_limit"),
        PromptArgument("review_style"),
    )

    override fun render(arguments: Map<String, String>): String {
        val deckName = arguments["deck_name"] ?: "Default"
        val cardLimit = arguments["card_limit"]?.let {
            it.trim().toIntOrNull() ?: throw PromptArgumentException("card_limit must be an integer, got '$it'")
        } ?: 20
        val style = arguments["review_style"].takeIf { it == "quick" || it == "voice" } ?: "interactive"
        val styleInstructions = when (style) {
            "voice" -> VOICE_INSTRUCTIONS
            "quick" -> QUICK_INSTRUCTIONS
            else -> INTERACTIVE_INSTRUCTIONS
        }
        return """You are helping the user conduct an Anki review session with the AnkiDroid collection on their phone.

SESSION PARAMETERS:
- Deck: "$deckName"
- Cards to review: up to $cardLimit
- Style: $style
$styleInstructions

WORKFLOW:
1. If the user also studies on other devices, suggest syncing in AnkiDroid first — it brings in reviews done there
2. Get the next due card: Use get_due_cards with deck_name="$deckName"
   - get_due_cards returns ONE card at a time in true scheduler order
   - It returns the question only - include_answer stays off during a review; it returns
     the answer before the user has responded
3. Use present_card to show the question to the user
4. Wait for their response
5. Use present_card with show_answer=True to reveal the answer
6. Evaluate their response and suggest a rating (1-4)
7. Once the user confirms the rating, use rate_card to record their performance
8. Repeat from step 2 to get the next card
9. Continue until no more cards are due or $cardLimit cards reviewed

SESSION RULES:
- Suggest syncing in AnkiDroid before starting if the user studies on other devices; the user may decline
- The answer is neither revealed nor hinted at until the user has responded to the question
- rate_card records the rating in Anki's scheduler immediately, so it comes after the answer was
  shown with present_card(show_answer=True) and the user confirmed the rating, not before
- Never call rate_card twice for the same answer: a second call records a second review
- Be encouraging but honest about mistakes
- If the user wants to stop early, that's fine - suggest syncing in AnkiDroid before ending
- Track progress: "Card X of Y completed"
- At the end, summarize the session (cards reviewed, performance distribution)

RATING GUIDE:
- Use the rating that best reflects the user's actual recall
- Don't inflate ratings to make them feel better
- Struggling learners benefit from honest ratings (it schedules more reviews)
- If unsure, rate "Hard" rather than "Again" for partial knowledge

Begin by suggesting a sync in AnkiDroid if the user studies elsewhere, then fetch the first due card for the "$deckName" deck."""
    }

    private const val VOICE_INSTRUCTIONS = """
VOICE REVIEW MODE:
- This is a voice-only session - the user cannot see the screen
- Read the card question aloud clearly
- Wait for the user's verbal answer
- Read the correct answer and evaluate their response
- get_due_cards reports has_images and has_audio for each card; if a card depends on an
  image or audio the user cannot hear or see, say so plainly instead of describing markup,
  ask the user whether to skip it, and if they agree use card_bury on that one card
- Rate cards based on quality of verbal recall:
  * Again (1): Completely forgot or major errors
  * Hard (2): Struggled but got it eventually
  * Good (3): Correct with reasonable effort
  * Easy (4): Instant, effortless recall"""

    private const val QUICK_INSTRUCTIONS = """
QUICK REVIEW MODE:
- Present cards rapidly with minimal discussion
- Show question, wait for user signal, show answer
- Rate based on user's quick self-assessment (Again/Hard/Good/Easy)
- Aim for efficient coverage without deep exploration"""

    private const val INTERACTIVE_INSTRUCTIONS = """
INTERACTIVE REVIEW MODE:
- Present each card's question and wait for the user's answer
- After they respond, reveal the answer and discuss if needed
- Help them understand concepts they struggle with
- Provide mnemonics or explanations when helpful
- Rate cards based on quality of their recall:
  * Again (1): Completely forgot or major errors
  * Hard (2): Struggled but got it eventually
  * Good (3): Correct with reasonable effort
  * Easy (4): Instant, effortless recall"""
}
