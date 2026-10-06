package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AssistantRouter] intent matching and dispatch.
 */
class AssistantRouterTest {

    @Test
    fun `note intent saves to task store`() {
        val store = TaskStore.createDummy()
        val router = AssistantRouter(
            taskStore = store
        )

        val result = router.route(Command(raw = "note: Buy oat milk"))
        assertTrue(result is CommandResult.Success)
        assertEquals("Note saved: 'Buy oat milk'", (result as CommandResult.Success).message)

        val keys = store.listNotes()
        assertTrue(keys.isNotEmpty())
    }

    @Test
    fun `task intent saves to task store`() {
        val store = TaskStore.createDummy()
        val router = AssistantRouter(
            taskStore = store
        )

        val result = router.route(Command(raw = "task: Fix build pipeline"))
        assertTrue(result is CommandResult.Success)
        assertEquals("Task saved: 'Fix build pipeline'", (result as CommandResult.Success).message)
    }

    @Test
    fun `remember intent saves to task store`() {
        val store = TaskStore.createDummy()
        val router = AssistantRouter(
            taskStore = store
        )

        val result = router.route(Command(raw = "remember meeting at 3pm"))
        assertTrue(result is CommandResult.Success)
        assertEquals("Remembered: 'meeting at 3pm'", (result as CommandResult.Success).message)
    }

    @Test
    fun `search intent triggers search handler`() {
        var searchedQuery: String? = null
        val router = AssistantRouter(
            searchAction = { q ->
                searchedQuery = q
                true
            }
        )

        val result = router.route(Command(raw = "search kotlin coroutines"))
        assertTrue(result is CommandResult.Success)
        assertEquals("Searching for 'kotlin coroutines'", (result as CommandResult.Success).message)
        assertEquals("kotlin coroutines", searchedQuery)
    }

    @Test
    fun `find intent triggers search handler`() {
        var searchedQuery: String? = null
        val router = AssistantRouter(
            searchAction = { q ->
                searchedQuery = q
                true
            }
        )

        val result = router.route(Command(raw = "find rust tutorial"))
        assertTrue(result is CommandResult.Success)
        assertEquals("Searching for 'rust tutorial'", (result as CommandResult.Success).message)
        assertEquals("rust tutorial", searchedQuery)
    }
}