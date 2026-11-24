package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val e2eeEnabled: Boolean = false,
    val e2eeSalt: String? = null,
    val e2eeVerifier: String? = null,
    val storageUsedBytes: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VaultFolder(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val colorHex: String = "#0D5C63",
    val iconName: String = "folder",
    val privateVault: Boolean = false,
    val tags: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VaultDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val fileCategory: String = "IMAGE",
    val fileSizeBytes: Long = 0L,
    val uploadDateMillis: Long = 0L,
    val folderId: String? = null,
    val aiCategory: String = "Uncategorized",
    val tags: List<String> = emptyList(),
    val extractedText: String = "",
    val summary: String = "",
    val thumbnailBase64: String = "",
    val hasCloudBlob: Boolean = false,
    val encrypted: Boolean = false,
    val encryptedPayload: String = "",
    val encryptionIv: String = "",
    val ocrStatus: String = "PENDING",
    val pageCount: Int = 1,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DocumentBlob(
    @DocumentId val id: String = "",
    val userId: String = "",
    val docId: String = "",
    val mimeType: String = "",
    val dataBase64: String = "",
    val encrypted: Boolean = false,
    val encryptionIv: String = "",
    val byteLength: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SavedWebsite(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val urlTemplate: String = "",
    val category: String = "Custom",
    val usageCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SearchHistoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val query: String = "",
    val mode: String = "SEARCH",
    val aiAnswerPreview: String = "",
    val resultCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

/**
 * UI-ready document view that transparently decrypts E2EE private folder entries
 * in memory when the user has unlocked the Private Vault.
 */
data class DecryptedDocumentView(
    val raw: VaultDocument,
    val displayTitle: String,
    val displaySummary: String,
    val displayExtractedText: String,
    val displayThumbnailBase64: String,
    val isLocked: Boolean
)

enum class DateFilterPreset(val label: String) {
    ALL("All dates"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year"),
    CUSTOM("Custom range")
}

object AiCategories {
    val ALL = listOf(
        "Receipts & Finance",
        "IDs & Travel",
        "Contracts & Legal",
        "Medical & Health",
        "Shipping & Tracking",
        "Work & Study",
        "Archives",
        "Personal",
        "Uncategorized"
    )
}
