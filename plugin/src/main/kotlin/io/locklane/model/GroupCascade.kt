package io.locklane.model

/**
 * Group-aware selection helpers for safe-update tables.
 *
 * Safe updates sharing a non-null [SafeUpdate.groupId] are interdependent:
 * the planner verified they resolve together but not as a proper subset.
 * The UI must apply the whole group or none of it, so checkbox toggles
 * cascade across peers.
 */
object GroupCascade {

    /**
     * Set [row]'s selection to [value] and cascade the change to every
     * peer row sharing the same non-null groupId. Mutates [selected]
     * in place. Returns the indices whose state actually changed.
     */
    fun toggleRow(
        data: List<SafeUpdate>,
        selected: BooleanArray,
        row: Int,
        value: Boolean,
    ): List<Int> {
        val changed = mutableListOf<Int>()
        if (selected[row] != value) {
            selected[row] = value
            changed.add(row)
        }
        val gid = data[row].groupId ?: return changed
        for (i in data.indices) {
            if (i == row) continue
            if (data[i].groupId == gid && selected[i] != value) {
                selected[i] = value
                changed.add(i)
            }
        }
        return changed
    }

    /**
     * Return the package names of every other safe update sharing [row]'s
     * non-null groupId. Empty when [row] is ungrouped or alone in its group.
     */
    fun peersOf(data: List<SafeUpdate>, row: Int): List<String> {
        val gid = data[row].groupId ?: return emptyList()
        val selfName = data[row].packageName
        return data
            .filter { it.groupId == gid && it.packageName != selfName }
            .map { it.packageName }
    }

    /**
     * After a bulk predicate-based selection, coerce each group to an
     * all-or-nothing state: if any member was deselected, deselect every
     * member. This preserves the invariant that groups never appear in
     * the apply set as a proper subset of themselves.
     */
    fun enforceGroupCoherence(data: List<SafeUpdate>, selected: BooleanArray) {
        val byGroup = HashMap<String, MutableList<Int>>()
        for (i in data.indices) {
            val gid = data[i].groupId ?: continue
            byGroup.getOrPut(gid) { mutableListOf() }.add(i)
        }
        for ((_, indices) in byGroup) {
            if (indices.any { !selected[it] }) {
                for (i in indices) selected[i] = false
            }
        }
    }
}
