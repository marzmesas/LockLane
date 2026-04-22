package io.locklane.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupCascadeTest {

    private fun update(name: String, groupId: String? = null): SafeUpdate =
        SafeUpdate(packageName = name, fromVersion = "1.0.0", toVersion = "2.0.0", groupId = groupId)

    @Test
    fun `toggling ungrouped row does not touch peers`() {
        val data = listOf(update("a"), update("b"), update("c"))
        val selected = booleanArrayOf(true, true, true)

        val changed = GroupCascade.toggleRow(data, selected, 0, false)

        assertEquals(listOf(0), changed)
        assertFalse(selected[0])
        assertTrue(selected[1])
        assertTrue(selected[2])
    }

    @Test
    fun `unchecking one grouped row cascades to peers`() {
        val data = listOf(
            update("a", "g1"),
            update("b", "g1"),
            update("c"),
        )
        val selected = booleanArrayOf(true, true, true)

        val changed = GroupCascade.toggleRow(data, selected, 0, false)

        assertEquals(setOf(0, 1), changed.toSet())
        assertFalse(selected[0])
        assertFalse(selected[1])
        assertTrue(selected[2])
    }

    @Test
    fun `checking one grouped row cascades to peers`() {
        val data = listOf(
            update("a", "g1"),
            update("b", "g1"),
            update("c"),
        )
        val selected = booleanArrayOf(false, false, false)

        val changed = GroupCascade.toggleRow(data, selected, 1, true)

        assertEquals(setOf(0, 1), changed.toSet())
        assertTrue(selected[0])
        assertTrue(selected[1])
        assertFalse(selected[2])
    }

    @Test
    fun `no-op toggle returns empty changed list`() {
        val data = listOf(update("a", "g1"), update("b", "g1"))
        val selected = booleanArrayOf(true, true)

        val changed = GroupCascade.toggleRow(data, selected, 0, true)

        assertTrue(changed.isEmpty())
    }

    @Test
    fun `different groups are independent`() {
        val data = listOf(
            update("a", "g1"),
            update("b", "g1"),
            update("c", "g2"),
            update("d", "g2"),
        )
        val selected = booleanArrayOf(true, true, true, true)

        GroupCascade.toggleRow(data, selected, 0, false)

        assertFalse(selected[0])
        assertFalse(selected[1])
        assertTrue(selected[2])
        assertTrue(selected[3])
    }

    @Test
    fun `enforceGroupCoherence deselects whole group if any member is off`() {
        val data = listOf(
            update("a", "g1"),
            update("b", "g1"),
            update("c"),
        )
        val selected = booleanArrayOf(true, false, true)

        GroupCascade.enforceGroupCoherence(data, selected)

        assertFalse(selected[0])
        assertFalse(selected[1])
        assertTrue(selected[2])
    }

    @Test
    fun `enforceGroupCoherence leaves fully-selected groups alone`() {
        val data = listOf(
            update("a", "g1"),
            update("b", "g1"),
            update("c", "g2"),
            update("d", "g2"),
        )
        val selected = booleanArrayOf(true, true, false, false)

        GroupCascade.enforceGroupCoherence(data, selected)

        assertTrue(selected[0])
        assertTrue(selected[1])
        assertFalse(selected[2])
        assertFalse(selected[3])
    }

    @Test
    fun `enforceGroupCoherence ignores ungrouped rows`() {
        val data = listOf(update("a"), update("b"), update("c"))
        val selected = booleanArrayOf(true, false, true)

        GroupCascade.enforceGroupCoherence(data, selected)

        assertTrue(selected[0])
        assertFalse(selected[1])
        assertTrue(selected[2])
    }
}
