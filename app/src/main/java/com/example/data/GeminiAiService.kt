package com.example.data

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AiDocumentAnalysis(
    val title: String,
    val extractedText: String,
    val summary: String,
    val category: String,
    val tags: List<String>
)

private const val TAG = "GeminiAiService"

/**
 * Firebase AI Logic service powered by Gemini 3.8 Flash.
 * Handles multimodal OCR text recognition, automated AI document categorization,
 * structured metadata extraction, and streaming search-bar document Q&A.
 */
class GeminiAiService {

    private val documentAnalysisSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Short, clear human-readable title for this document (max 60 chars)."),
            "extractedText" to Schema.string(description = "Complete, accurate verbatim OCR text extracted from all visible content in the document, preserving numbers, tracking IDs, dates, amounts, and addresses."),
            "summary" to Schema.string(description = "Concise 1-2 sentence summary highlighting key facts, dates, reference numbers, or totals."),
            "category" to Schema.enumeration(
                values = AiCategories.ALL,
                description = "Most appropriate category grouping similar documents together."
            ),
            "tags" to Schema.array(
                items = Schema.string(),
                description = "Up to 6 short lowercase searchable tags (e.g., vendor name, document type, year, tracking)."
            )
        )
    )

    private val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = documentAnalysisSchema
            }
        )
    }

    private val qaModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash"
        )
    }

    /**
     * Performs full multimodal OCR and automated AI categorization on one or more document bitmaps
     * (images, camera captures, or rendered PDF pages).
     */
    suspend fun analyzeDocumentBitmaps(
        bitmaps: List<Bitmap>,
        fileName: String,
        fileCategory: String
    ): Result<AiDocumentAnalysis> {
        return try {
            val response = withTimeout(60_000L) {
                structuredModel.generateContent(
                    content {
                        text(
                            "You are DocVault's precision OCR and document intelligence engine. " +
                                "Inspect the uploaded $fileCategory file ('$fileName'). " +
                                "1) Transcribe ALL readable text accurately into extractedText (including tracking codes, invoice numbers, dates, phone numbers, URLs, totals, names). " +
                                "2) Produce a crisp title, 1-2 sentence summary, exact category from the schema enum, and 3-6 concise lowercase tags."
                        )
                        bitmaps.take(3).forEach { bmp ->
                            image(bmp)
                        }
                    }
                )
            }
            val jsonText = response.text
                ?: throw IllegalStateException("Gemini returned an empty response.")
            Result.success(parseAnalysisJson(jsonText, fileName, ""))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Document OCR analysis timed out", e)
            Result.failure(Exception("AI OCR scan timed out after 60 seconds. You can retry scan from the document view."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Document OCR analysis failed", e)
            Result.failure(e)
        }
    }

    /**
     * Categorizes and summarizes already-extracted text from DOCX, RAR/ZIP manifests, or TXT files.
     */
    suspend fun analyzeExtractedText(
        rawText: String,
        fileName: String,
        fileCategory: String
    ): Result<AiDocumentAnalysis> {
        return try {
            val truncated = rawText.take(24_000)
            val response = withTimeout(60_000L) {
                structuredModel.generateContent(
                    "You are DocVault's document organizer. Analyze this $fileCategory file named '$fileName'. " +
                        "Return a concise title, a 1-2 sentence summary, the best category from the enum, up to 6 short lowercase tags, " +
                        "and keep extractedText as a clean searchable version of the key content.\n\nCONTENT:\n$truncated"
                )
            }
            val jsonText = response.text
                ?: throw IllegalStateException("Gemini returned an empty response.")
            val parsed = parseAnalysisJson(jsonText, fileName, rawText)
            // Keep full locally parsed text if it is richer than the returned snippet
            val bestText = if (rawText.length > parsed.extractedText.length) rawText else parsed.extractedText
            Result.success(parsed.copy(extractedText = bestText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Text analysis timed out", e)
            Result.failure(Exception("AI categorization timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Text analysis failed", e)
            Result.failure(e)
        }
    }

    /**
     * In-built Search Bar AI Assistant: streams direct grounded answers from the user's matched documents.
     */
    fun askDocumentsStream(
        question: String,
        matchedDocs: List<DecryptedDocumentView>
    ): Flow<String> = flow {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val contextBlock = buildString {
            if (matchedDocs.isEmpty()) {
                appendLine("No unlocked documents are available in the user's vault yet.")
            } else {
                matchedDocs.forEachIndexed { idx, doc ->
                    val dateStr = dateFmt.format(Date(doc.raw.uploadDateMillis))
                    appendLine("=== DOCUMENT ${idx + 1} ===")
                    appendLine("Title: ${doc.displayTitle}")
                    appendLine("File: ${doc.raw.fileName} (${doc.raw.fileCategory}) | Upload Date: $dateStr")
                    appendLine("Category: ${doc.raw.aiCategory} | Tags: ${doc.raw.tags.joinToString(", ")}")
                    if (doc.displaySummary.isNotBlank()) {
                        appendLine("Summary: ${doc.displaySummary}")
                    }
                    if (doc.displayExtractedText.isNotBlank()) {
                        appendLine("Extracted Content:\n${doc.displayExtractedText.take(3500)}")
                    }
                    appendLine()
                }
            }
        }

        val prompt = buildString {
            appendLine("You are DocVault's built-in AI document assistant inside the search bar.")
            appendLine("Answer the user's question directly, concisely, and accurately using ONLY the provided vault documents.")
            appendLine("Always mention the exact document title and upload date where you found each detail (e.g. tracking numbers, totals, dates, IDs, addresses).")
            appendLine("If the requested detail is not present in the provided documents, say so clearly and suggest what document or keyword to upload/search.")
            appendLine()
            appendLine("USER QUESTION: $question")
            appendLine()
            appendLine("VAULT DOCUMENTS CONTEXT:")
            append(contextBlock)
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            qaModel.generateContentStream(prompt).collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    accumulated.append(piece)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("AI assistant returned an empty response.")
        }
    }

    private fun parseAnalysisJson(
        jsonString: String,
        fileName: String,
        fallbackText: String
    ): AiDocumentAnalysis {
        val obj = JSONObject(jsonString)
        val title = obj.optString("title", "").trim()
            .ifBlank { fileName.substringBeforeLast('.').ifBlank { fileName } }
            .take(180)
        val extracted = obj.optString("extractedText", "").trim().ifBlank { fallbackText }
        val summary = obj.optString("summary", "").trim().take(1800)
        val rawCategory = obj.optString("category", "Uncategorized").trim()
        val category = AiCategories.ALL.firstOrNull { it.equals(rawCategory, ignoreCase = true) }
            ?: "Uncategorized"
        val tagsJson = obj.optJSONArray("tags")
        val tags = mutableListOf<String>()
        if (tagsJson != null) {
            for (i in 0 until tagsJson.length()) {
                val t = tagsJson.optString(i, "").trim().lowercase().take(32)
                if (t.isNotEmpty() && t !in tags) tags.add(t)
            }
        }
        return AiDocumentAnalysis(
            title = title,
            extractedText = extracted.take(95_000),
            summary = summary,
            category = category,
            tags = tags.take(8)
        )
    }
}
