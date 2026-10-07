package com.coucou.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Routes user commands to the appropriate handler.
 *
 * Supported intents:
 *   GREETING: "hi", "hello", "coucou", "hey" -> trigger Mochi GREET + sound
 *   NOTE/TASK: "note: <text>" / "task: <text>" / "remember <text>" -> Persist to TaskStore
 *   APP LAUNCH: "open <app>" / "launch <app>" -> Query cached AppLauncher index
 *   SEARCH: "search <query>" / "find <query>" -> Browser search Intent
 *   FALLBACK: Friendly assistant message; NEVER "No app found" unless explicit app-launch mode
 */
class AssistantRouter(
    private val context: Context? = null,
    private val taskStore: TaskStore = context?.let { TaskStore.create(it) } ?: TaskStore.createDummy(),
    private val launcher: AppLauncher? = context?.let { AppLauncher(it) },
    private val searchAction: ((String) -> Boolean)? = null
) : CommandRouter {

    override val name: String = "AssistantRouter"

    override fun route(command: Command): CommandResult {
        val rawInput = command.raw.trim()
        if (rawInput.isEmpty()) {
            return CommandResult.Unknown(command.raw)
        }

        val q = rawInput.lowercase()

        // GREETING intent: "hi", "hello", "coucou", "hey"
        when {
            q == "hi" || q == "hello" || q == "coucou" || q == "hey" -> {
                // Trigger the character GREET state + sound via the engine
                // The actual animation is handled by OverlayService.characterView?.greet()
                return CommandResult.Success("Coucou! How can I help you?")
            }
        }

        // Task/Note intent: "note: <text>" / "note <text>" / "task: <text>" / "task <text>" / "remember <text>"
        when {
            q.startsWith("note:") || q.startsWith("note ") -> {
                val prefixLen = if (q.startsWith("note:")) 5 else 5
                val text = rawInput.substring(prefixLen).trim()
                taskStore.saveNote("note_${System.currentTimeMillis()}", text)
                return CommandResult.Success("Note saved: '$text'")
            }
            q.startsWith("task:") || q.startsWith("task ") -> {
                val prefixLen = if (q.startsWith("task:")) 5 else 5
                val text = rawInput.substring(prefixLen).trim()
                taskStore.saveNote("task_${System.currentTimeMillis()}", text)
                return CommandResult.Success("Task saved: '$text'")
            }
            q.startsWith("remember:") || q.startsWith("remember ") -> {
                val prefixLen = if (q.startsWith("remember:")) 9 else 9
                val text = rawInput.substring(prefixLen).trim()
                taskStore.saveNote("remember_${System.currentTimeMillis()}", text)
                return CommandResult.Success("Remembered: '$text'")
            }
        }

        // Search intent: "search <query>" / "find <query>"
        when {
            q.startsWith("search ") -> {
                val query = rawInput.substring(7).trim()
                return handleSearch(query)
            }
            q.startsWith("find ") -> {
                val query = rawInput.substring(5).trim()
                return handleSearch(query)
            }
        }

        // App intent: "open <app>" / "launch <app>"
        val words = q.split(' ', limit = 2)
        if (words.first() in setOf("open", "launch")) {
            val appName = words.getOrNull(1)?.trim() ?: return CommandResult.Unknown(command.raw)
            if (appName.isNotEmpty()) {
                val app = launcher?.launch(appName)
                return when (app) {
                    is AppEntry -> CommandResult.Success(app.label)
                    else -> CommandResult.Unknown(rawInput)
                }
            }
        }

        // FALLBACK: friendly assistant message - NEVER "No app found" unless explicit app-launch mode
        return CommandResult.Success("I'm not sure what you mean. Try 'hello', 'open <app>', 'note: ...', or 'search <query>'.")
    }

    private fun handleSearch(query: String): CommandResult {
        if (searchAction != null) {
            return if (searchAction.invoke(query)) {
                CommandResult.Success("Searching for '$query'")
            } else {
                CommandResult.Failed("Could not start search")
            }
        }
        val ctx = context ?: return CommandResult.Failed("Context not available for search")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            return CommandResult.Success("Searching for '$query'")
        } catch (e: Exception) {
            Log.e("AssistantRouter", "Failed to start search", e)
            return CommandResult.Failed("Could not start search")
        }
    }
}