package ro.yt.downloader

/**
 * Compară versiuni de forma „1.2.3”, „v1.2”, fără semver complet (suffixe după „-” sunt ignorate la comparare).
 */
object AppVersion {

    fun normalizeForCompare(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .trim()

    private fun parts(version: String): List<Int> =
        normalizeForCompare(version).split(".").map { seg ->
            seg.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        }

    /** &lt; 0 dacă [a] &lt; [b], 0 egal, &gt; 0 dacă [a] &gt; [b]. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size, 1)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
