package com.coucou.android

/**
 * A single parsed user instruction, e.g. "chrome" or "open chrome".
 */
data class Command(
    val raw: String,
    val verb: String? = null,
    val argument: String? = null
)

/**
 * Outcome of routing a [Command]. Deliberately explicit rather than a bare Boolean so the
 * UI layer can distinguish "no match" from "matched but failed to launch", which need
 * different user feedback.
 */
sealed class CommandResult {
    /** Command was understood and the action was carried out. */
    data class Success(val message: String? = null) : CommandResult()

    /** Command parsed cleanly but no handler claimed it. */
    data class Unknown(val query: String) : CommandResult()

    /** Command matched a handler, but the action could not be completed. */
    data class Failed(val reason: String) : CommandResult()
}

/**
 * Routes a user instruction to the component that can fulfil it.
 *
 * Sprint 1 ships a single stub handler (launch an app by name); later sprints add
 * more handlers without changing [OverlayService].
 */
interface CommandRouter {

    /** Human-readable name of this handler, used for diagnostics. */
    val name: String

    /**
     * Attempts to fulfil [command].
     *
     * @return [CommandResult.Success] if handled, [CommandResult.Unknown] if this
     *   handler does not recognise the command, or [CommandResult.Failed] if it
     *   recognised the command but could not complete it.
     */
    fun route(command: Command): CommandResult

    /** Convenience: parse [input] then route it. */
    fun route(input: String): CommandResult = route(parse(input))

    companion object {
        private val VERBS = setOf("open", "launch", "start", "run", "go")

        /**
         * Splits free text into an optional verb and argument. Accepts both
         * "chrome" and "open chrome" (and other common verbs).
         */
        fun parse(input: String): Command {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return Command(raw = "")
            }
            val firstSpace = trimmed.indexOf(' ')
            if (firstSpace <= 0) {
                return Command(raw = trimmed, argument = trimmed)
            }
            val verb = trimmed.substring(0, firstSpace).lowercase()
            val argument = trimmed.substring(firstSpace + 1).trim()
            return if (argument.isNotEmpty() && verb in VERBS) {
                Command(raw = trimmed, verb = verb, argument = argument)
            } else if (argument.isEmpty()) {
                // A bare verb with nothing after it ("open ") has no target at all;
                // do not fall back to treating the verb itself as the app name.
                Command(raw = trimmed, verb = verb, argument = null)
            } else {
                Command(raw = trimmed, argument = trimmed)
            }
        }
    }
}

/**
 * Dispatches to an ordered list of [CommandRouter] handlers, first match wins.
 */
class DefaultCommandRouter(
    private val handlers: List<CommandRouter>
) : CommandRouter {

    override val name: String = "DefaultCommandRouter"

    override fun route(command: Command): CommandResult {
        val argument = command.argument
        if (argument.isNullOrBlank()) {
            return CommandResult.Unknown(command.raw)
        }
        for (handler in handlers) {
            when (val result = handler.route(command)) {
                is CommandResult.Success -> return result
                is CommandResult.Failed -> return result
                is CommandResult.Unknown -> continue
            }
        }
        return CommandResult.Unknown(command.raw)
    }
}