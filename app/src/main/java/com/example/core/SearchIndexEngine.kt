package com.example.core

import com.example.data.DecryptedDocumentView
import java.util.BitSet

data class SearchFilter(
    val query: String = "",
    val folderId: String? = null,
    val onlyPrivateVault: Boolean = false,
    val aiCategory: String? = null,
    val fileCategory: String? = null,
    val tag: String? = null,
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null,
    val sortDescending: Boolean = true
)

data class SearchHit(
    val document: DecryptedDocumentView,
    val score: Int,
    val matchSnippet: String?
)

/**
 * High-speed inverted token index built for instant filtering & ranking across thousands of documents.
 * Runs off the main thread and answers prefix + keyword + date + metadata queries in milliseconds.
 */
class SearchIndexEngine {
    private var indexedDocs: List<DecryptedDocumentView> = emptyList()
    private var invertedIndex: Map<String, BitSet> = emptyMap()
    private var sortedTokens: List<String> = emptyList()
    private var lowerCorpus: Array<String> = emptyArray()
    private var lowerTitles: Array<String> = emptyArray()

    fun rebuild(docs: List<DecryptedDocumentView>) {
        indexedDocs = docs
        val tokenMap = HashMap<String, BitSet>(docs.size * 12)
        val corpus = Array(docs.size) { "" }
        val titles = Array(docs.size) { "" }

        docs.forEachIndexed { idx, doc ->
            val titleLow = doc.displayTitle.lowercase()
            titles[idx] = titleLow
            val combined = buildString {
                append(titleLow).append(' ')
                append(doc.raw.fileName.lowercase()).append(' ')
                append(doc.raw.aiCategory.lowercase()).append(' ')
                append(doc.raw.fileCategory.lowercase()).append(' ')
                doc.raw.tags.forEach { append(it.lowercase()).append(' ') }
                append(doc.displaySummary.lowercase()).append(' ')
                append(doc.displayExtractedText.lowercase())
            }
            corpus[idx] = combined

            val tokens = tokenize(combined)
            for (token in tokens) {
                var bs = tokenMap[token]
                if (bs == null) {
                    bs = BitSet(docs.size)
                    tokenMap[token] = bs
                }
                bs.set(idx)
            }
        }

        lowerCorpus = corpus
        lowerTitles = titles
        invertedIndex = tokenMap
        sortedTokens = tokenMap.keys.sorted()
    }

    fun search(filter: SearchFilter): List<SearchHit> {
        val docs = indexedDocs
        if (docs.isEmpty()) return emptyList()

        val rawQuery = filter.query.trim().lowercase()
        val queryTokens = if (rawQuery.isNotEmpty()) tokenize(rawQuery) else emptyList()

        // Fast candidate narrowing using inverted prefix index when keywords are provided
        val candidates: BitSet = if (queryTokens.isEmpty()) {
            BitSet(docs.size).apply { set(0, docs.size) }
        } else {
            var intersection: BitSet? = null
            for (qToken in queryTokens) {
                val unionForToken = BitSet(docs.size)
                val exact = invertedIndex[qToken]
                if (exact != null) unionForToken.or(exact)

                // Binary search prefix matches in sorted token vocabulary
                val startIdx = sortedTokens.binarySearch(qToken).let { if (it < 0) -it - 1 else it }
                var i = startIdx
                while (i < sortedTokens.size && sortedTokens[i].startsWith(qToken)) {
                    invertedIndex[sortedTokens[i]]?.let { unionForToken.or(it) }
                    i++
                }

                // Substring fallback if token has no prefix match (e.g. searching middle of a tracking ID)
                if (unionForToken.isEmpty && qToken.length >= 2) {
                    for (idx in lowerCorpus.indices) {
                        if (lowerCorpus[idx].contains(qToken)) {
                            unionForToken.set(idx)
                        }
                    }
                }

                if (intersection == null) {
                    intersection = unionForToken
                } else {
                    intersection.and(unionForToken)
                }
                if (intersection.isEmpty) break
            }
            intersection ?: BitSet()
        }

        val hits = ArrayList<SearchHit>(minOf(docs.size, 64))
        var idx = candidates.nextSetBit(0)
        while (idx >= 0) {
            val doc = docs[idx]
            if (matchesFilters(doc, filter)) {
                val score = if (rawQuery.isEmpty()) 0 else computeScore(idx, rawQuery, queryTokens)
                val snippet = if (rawQuery.isNotEmpty()) extractSnippet(doc.displayExtractedText, rawQuery, queryTokens) else null
                hits.add(SearchHit(doc, score, snippet))
            }
            idx = candidates.nextSetBit(idx + 1)
        }

        return if (rawQuery.isNotEmpty()) {
            hits.sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenByDescending { if (filter.sortDescending) it.document.raw.uploadDateMillis else -it.document.raw.uploadDateMillis }
            )
        } else {
            if (filter.sortDescending) {
                hits.sortedByDescending { it.document.raw.uploadDateMillis }
            } else {
                hits.sortedBy { it.document.raw.uploadDateMillis }
            }
        }
    }

    /**
     * Retrieves top relevant documents to ground the in-built Search Bar Gemini AI Q&A.
     */
    fun topRelevantForAi(question: String, maxDocs: Int = 8): List<DecryptedDocumentView> {
        val docs = indexedDocs.filter { !it.isLocked }
        if (docs.isEmpty()) return emptyList()
        val stopWords = setOf(
            "the", "is", "are", "was", "were", "what", "when", "where", "who", "how",
            "can", "you", "my", "me", "for", "from", "with", "about", "find", "show",
            "give", "tell", "info", "information", "document", "photo", "pic", "image",
            "in", "on", "of", "to", "a", "an", "and", "or", "do", "i", "have"
        )
        val tokens = tokenize(question.lowercase()).filter { it.length >= 2 && it !in stopWords }
        if (tokens.isEmpty()) {
            return docs.sortedByDescending { it.raw.uploadDateMillis }.take(maxDocs)
        }

        val scored = docs.map { doc ->
            val text = "${doc.displayTitle} ${doc.raw.fileName} ${doc.raw.aiCategory} ${doc.raw.tags.joinToString(" ")} ${doc.displaySummary} ${doc.displayExtractedText}".lowercase()
            var score = 0
            for (t in tokens) {
                if (doc.displayTitle.lowercase().contains(t)) score += 6
                if (doc.raw.tags.any { it.lowercase().contains(t) }) score += 5
                if (text.contains(t)) score += 3
            }
            doc to score
        }

        val matched = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxDocs)

        return matched.ifEmpty { docs.sortedByDescending { it.raw.uploadDateMillis }.take(maxDocs) }
    }

    private fun matchesFilters(doc: DecryptedDocumentView, f: SearchFilter): Boolean {
        if (f.onlyPrivateVault && !doc.raw.encrypted) return false
        if (f.folderId != null && doc.raw.folderId != f.folderId) return false
        if (f.aiCategory != null && !doc.raw.aiCategory.equals(f.aiCategory, ignoreCase = true)) return false
        if (f.fileCategory != null && !doc.raw.fileCategory.equals(f.fileCategory, ignoreCase = true)) return false
        if (f.tag != null && doc.raw.tags.none { it.equals(f.tag, ignoreCase = true) }) return false
        if (f.startDateMillis != null && doc.raw.uploadDateMillis < f.startDateMillis) return false
        if (f.endDateMillis != null && doc.raw.uploadDateMillis > f.endDateMillis) return false
        return true
    }

    private fun computeScore(idx: Int, rawQuery: String, tokens: List<String>): Int {
        var score = 0
        val title = lowerTitles[idx]
        val corpus = lowerCorpus[idx]
        if (title == rawQuery) score += 100
        else if (title.contains(rawQuery)) score += 50
        if (corpus.contains(rawQuery)) score += 25
        for (t in tokens) {
            if (title.contains(t)) score += 15
            else if (corpus.contains(t)) score += 5
        }
        return score
    }

    private fun extractSnippet(text: String, rawQuery: String, tokens: List<String>): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        var pos = lower.indexOf(rawQuery)
        if (pos < 0) {
            for (t in tokens) {
                pos = lower.indexOf(t)
                if (pos >= 0) break
            }
        }
        if (pos < 0) return null
        val start = maxOf(0, pos - 36)
        val end = minOf(text.length, pos + 110)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return "$prefix${text.substring(start, end).replace('\n', ' ').trim()}$suffix"
    }

    private fun tokenize(input: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in input) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (sb.isNotEmpty()) {
                result.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}
