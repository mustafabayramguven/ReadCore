package com.readcore.app

/**
 * Format-neutral reading positions, shared by the Android and web readers.
 *
 * A text position is "chapter + progression", where progression is the fraction (0..1) of the
 * chapter's non-whitespace characters that come before the reading point. Counting only
 * non-whitespace characters keeps the Android text extraction and the web DOM in agreement even
 * though they lay out spaces and line breaks differently.
 */
object Locator {
    fun paragraphs(chapter: String): List<String> =
        chapter.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }

    fun weight(text: String): Int = text.count { !it.isWhitespace() }

    /** Progression of a point [within] (0..1) of paragraph [index]. */
    fun progressionOf(weights: List<Int>, index: Int, within: Double): Double {
        val total = weights.sum()
        if (total <= 0 || weights.isEmpty()) return 0.0
        val i = index.coerceIn(0, weights.size - 1)
        val before = weights.take(i).sum()
        return ((before + weights[i] * within.coerceIn(0.0, 1.0)) / total).coerceIn(0.0, 1.0)
    }

    /** Paragraph index and the fraction inside it that hold [progression]. */
    fun locate(weights: List<Int>, progression: Double): Pair<Int, Double> {
        val total = weights.sum()
        if (total <= 0 || weights.isEmpty()) return 0 to 0.0
        val target = progression.coerceIn(0.0, 1.0) * total
        var before = 0.0
        for ((i, w) in weights.withIndex()) {
            if (w > 0 && target < before + w) return i to ((target - before) / w).coerceIn(0.0, 1.0)
            before += w
        }
        return weights.lastIndex to 1.0
    }

    /** Whole-book percent (0..100) weighted by chapter length, like Play Books' percentage. */
    fun percent(chapterWeights: List<Int>, chapter: Int, progression: Double): Double {
        val total = chapterWeights.sum()
        if (total <= 0 || chapterWeights.isEmpty()) return 0.0
        val c = chapter.coerceIn(0, chapterWeights.size - 1)
        val before = chapterWeights.take(c).sum()
        return ((before + chapterWeights[c] * progression.coerceIn(0.0, 1.0)) * 100.0 / total).coerceIn(0.0, 100.0)
    }

    /** True when two positions are far enough apart that offering a jump is worthwhile. */
    fun differs(chapterA: Int, progressionA: Double?, chapterB: Int, progressionB: Double?): Boolean =
        chapterA != chapterB || kotlin.math.abs((progressionA ?: 0.0) - (progressionB ?: 0.0)) > 0.02
}
