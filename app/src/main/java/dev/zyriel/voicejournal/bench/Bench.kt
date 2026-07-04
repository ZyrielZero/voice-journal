package dev.zyriel.voicejournal.bench

/**
 * Benchmark core. Pure Kotlin so the measurement discipline itself is
 * unit-tested: warmup runs are discarded, the median (not mean) of the
 * measured runs is reported, and raw runs are preserved in the output so
 * a committed file can be re-examined later.
 */
object Bench {

    data class Result(
        val name: String,
        val runsMs: List<Long>,
        val medianMs: Long,
        /** Optional context, e.g. clip seconds, corpus size, realtime factor. */
        val extra: Map<String, String> = emptyMap(),
    )

    fun median(xs: List<Long>): Long {
        require(xs.isNotEmpty()) { "median of empty list" }
        val s = xs.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** Runs [block] [warmups] times untimed, then [runs] times timed. */
    inline fun measure(
        name: String,
        warmups: Int = 2,
        runs: Int = 5,
        extra: Map<String, String> = emptyMap(),
        block: () -> Unit,
    ): Result {
        require(runs > 0)
        repeat(warmups) { block() }
        val times = ArrayList<Long>(runs)
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            times.add((System.nanoTime() - t0) / 1_000_000)
        }
        return Result(name, times, median(times), extra)
    }

    /** Times a single one-shot event (model load, engine init). */
    inline fun once(name: String, block: () -> Unit): Result {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Result(name, listOf(ms), ms, mapOf("kind" to "one-shot"))
    }

    /** Flat, dependency-free JSON. Keys and values are escaped minimally. */
    fun toJson(meta: Map<String, String>, results: List<Result>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val sb = StringBuilder()
        sb.append("{\n  \"meta\": {\n")
        meta.entries.joinTo(sb, ",\n") { (k, v) -> "    \"${esc(k)}\": \"${esc(v)}\"" }
        sb.append("\n  },\n  \"results\": [\n")
        results.joinTo(sb, ",\n") { r ->
            buildString {
                append("    {\"name\": \"${esc(r.name)}\", ")
                append("\"median_ms\": ${r.medianMs}, ")
                append("\"runs_ms\": [${r.runsMs.joinToString(",")}]")
                if (r.extra.isNotEmpty()) {
                    append(", \"extra\": {")
                    r.extra.entries.joinTo(this, ", ") { (k, v) -> "\"${esc(k)}\": \"${esc(v)}\"" }
                    append("}")
                }
                append("}")
            }
        }
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }
}
