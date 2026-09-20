package bes.max.bmaps.core.mapengine

internal class OverlayRegistry<T>(
    private val id: (T) -> String,
    private val add: (T) -> Unit,
    private val remove: (String) -> Unit,
) {
    private var current = emptyMap<String, T>()

    fun sync(values: List<T>) {
        val next = values.associateBy(id)
        current.forEach { (key, value) -> if (next[key] != value) remove(key) }
        next.forEach { (key, value) -> if (current[key] != value) add(value) }
        current = next
    }

    fun clear() { current.keys.forEach(remove); current = emptyMap() }
}
