package dev.bmcreations.protovalidate

object CollectionValidators {

    // ── Repeated ──

    fun checkMinItems(count: Int, min: Long, field: String): FieldViolation? {
        if (count.toLong() < min) {
            return FieldViolation(field, "repeated.min_items", "list must have at least $min items")
        }
        return null
    }

    fun checkMaxItems(count: Int, max: Long, field: String): FieldViolation? {
        if (count.toLong() > max) {
            return FieldViolation(field, "repeated.max_items", "list must have at most $max items")
        }
        return null
    }

    fun <T> checkUnique(list: List<T>, field: String): FieldViolation? {
        if (list.size != list.toSet().size) {
            return FieldViolation(field, "repeated.unique", "list items must be unique")
        }
        return null
    }

    // ── Map ──

    fun checkMinPairs(count: Int, min: Long, field: String): FieldViolation? {
        if (count.toLong() < min) {
            return FieldViolation(field, "map.min_pairs", "map must be at least $min entries")
        }
        return null
    }

    fun checkMaxPairs(count: Int, max: Long, field: String): FieldViolation? {
        if (count.toLong() > max) {
            return FieldViolation(field, "map.max_pairs", "map must be at most $max entries")
        }
        return null
    }
}
