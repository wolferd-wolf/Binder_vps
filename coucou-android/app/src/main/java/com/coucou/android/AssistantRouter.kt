package com.coucou.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Routes user commands to the appropriate handler.
 *
 * Supported intents:
 *   "open <app>"   / "launch <app>"       -> Launch app via cached index
 *   "note: <text>" / "task: <text>" / "remember <text>" -> Persist to TaskStore
 *   "search <query>" / "find <query>" -> Browser search Intent
 *   conversational -> Chat fallback pipeline
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

        // Task/Note intent: "note: <text>" / "task: <text>" / "remember <text>"
        when {
            q.startsWith("note:") -> {
                val text = rawInput.substring(5).trim()
                taskStore.saveNote("note_${System.currentTimeMillis()}", text)
                return CommandResult.Success("Note saved: '$text'")
            }
            q.startsWith("task:") -> {
                val text = rawInput.substring(5).trim()
                taskStore.saveNote("task_${System.currentTimeMillis()}", text)
                return CommandResult.Success("Task saved: '$text'")
            }
            q.startsWith("remember") -> {
                val text = rawInput.substring(8).trim()
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

        // Chat fallback: conversational queries
        return routeConversational(command.raw)
    }

    private fun routeConversational(query: String): CommandResult {
        val lower = query.lowercase()
        // Common pattern: "open chrome" / "launch chrome" already handled above
        if (lower.contains("chrome") || lower.contains("browser")) {
            return handleSearch(query.replace("chrome", "").replace("browser", "").trim())
        }
        if (lower.contains("settings")) {
            val app = launcher?.launch("settings")
            return when (app) {
                is AppEntry -> CommandResult.Success(app.label)
                else -> CommandResult.Failed("Could not open settings")
            }
        }
        // Default: unknown command, let other handlers try
        return CommandResult.Unknown(query)
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