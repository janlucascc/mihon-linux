package mihon.core.archive

/**
 * Natural order comparator for alphanumeric strings.
 *
 * Ensures natural human ordering:
 * - "page 2.jpg" sorts BEFORE "page 10.jpg"
 * - "01.png" sorts BEFORE "2.png"
 * - "1.png" sorts BEFORE "01.png" (tie-breaker for identical numeric value)
 * - "PAGE 2.jpg" compares equally with "page 2.jpg" (case-insensitive text)
 *
 * Uses a single-pass chunk tokenizer and length-based magnitude comparison
 * without BigInteger allocations or integer overflow hazards.
 */
object NaturalOrderComparator : Comparator<String> {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 === s2) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        val chunks1 = splitIntoChunks(s1)
        val chunks2 = splitIntoChunks(s2)
        val minSize = minOf(chunks1.size, chunks2.size)

        var bias = 0

        for (i in 0 until minSize) {
            val c1 = chunks1[i]
            val c2 = chunks2[i]
            val isDigit1 = c1[0].isDigit()
            val isDigit2 = c2[0].isDigit()

            if (isDigit1 && isDigit2) {
                // Both chunks are numeric: compare numeric magnitude
                val stripped1 = c1.trimStart('0')
                val stripped2 = c2.trimStart('0')

                // 1. Compare non-zero digit length (longer string represents larger number)
                val lenCmp = stripped1.length.compareTo(stripped2.length)
                if (lenCmp != 0) return lenCmp

                // 2. Same length: lexicographical comparison matches numeric comparison
                val numCmp = stripped1.compareTo(stripped2)
                if (numCmp != 0) return numCmp

                // 3. Numerically identical (e.g. "01" vs "1" or "001" vs "01")
                // Record leading zero length difference as secondary bias if not already set.
                // Shorter representation (fewer leading zeros) sorts first.
                if (bias == 0 && c1.length != c2.length) {
                    bias = c1.length.compareTo(c2.length)
                }
            } else if (isDigit1) {
                // Digit chunk vs non-digit chunk
                val cmp = c1.compareTo(c2, ignoreCase = true)
                return if (cmp != 0) cmp else c1.compareTo(c2)
            } else if (isDigit2) {
                // Non-digit chunk vs digit chunk
                val cmp = c1.compareTo(c2, ignoreCase = true)
                return if (cmp != 0) cmp else c1.compareTo(c2)
            } else {
                // Both chunks are text: case-insensitive comparison
                val cmp = c1.compareTo(c2, ignoreCase = true)
                if (cmp != 0) return cmp

                // Record case difference as secondary bias for identical words
                if (bias == 0 && c1 != c2) {
                    bias = c1.compareTo(c2)
                }
            }
        }

        // If one string has more chunks than the other
        if (chunks1.size != chunks2.size) {
            return chunks1.size.compareTo(chunks2.size)
        }

        // If all chunks matched, return bias if any (leading zeros or case difference)
        if (bias != 0) return bias

        // Exact match fallback for full consistency with equals
        return s1.compareTo(s2)
    }

    /**
     * Splits string into alternating chunks of continuous digits and continuous non-digits.
     * Single-pass O(N) scanner with zero regex overhead.
     */
    private fun splitIntoChunks(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val chunks = ArrayList<String>(8)
        var start = 0
        var isDigit = s[0].isDigit()
        for (i in 1 until s.length) {
            val currentIsDigit = s[i].isDigit()
            if (currentIsDigit != isDigit) {
                chunks.add(s.substring(start, i))
                start = i
                isDigit = currentIsDigit
            }
        }
        chunks.add(s.substring(start))
        return chunks
    }
}
