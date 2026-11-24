package com.example.data

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class VaultRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("User must be signed in with Google before accessing DocVault cloud data.")
        }
        return uid
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    fun newDocumentId(uid: String): String =
        userDoc(uid).collection("documents").document().id

    // --- Real-Time Synced Streams ---

    fun observeProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId"
        emitAll(
            userDoc(userId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    handleStreamError(error, OperationType.GET, path, userId)
                    throw error
                }
        )
    }

    fun observeFolders(userId: String): Flow<List<VaultFolder>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/folders"
        emitAll(
            userDoc(userId).collection("folders")
                .snapshots()
                .map { snap ->
                    snap.toObjects(VaultFolder::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.name.lowercase() }
                }
                .catch { error ->
                    handleStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeDocuments(userId: String): Flow<List<VaultDocument>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/documents"
        emitAll(
            userDoc(userId).collection("documents")
                .snapshots()
                .map { snap ->
                    snap.toObjects(VaultDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.uploadDateMillis }
                }
                .catch { error ->
                    handleStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeSavedWebsites(userId: String): Flow<List<SavedWebsite>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/saved_websites"
        emitAll(
            userDoc(userId).collection("saved_websites")
                .snapshots()
                .map { snap ->
                    snap.toObjects(SavedWebsite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.usageCount }
                }
                .catch { error ->
                    handleStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeSearchHistory(userId: String): Flow<List<SearchHistoryEntry>> = flow {
        require(userId.isNotBlank())
        val path = "users/$userId/search_history"
        emitAll(
            userDoc(userId).collection("search_history")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(60)
                .snapshots()
                .map { snap ->
                    snap.toObjects(SearchHistoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    handleStreamError(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    private fun handleStreamError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val ex = firestoreError ?: (error as? Exception)
        if (ex != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(ex, operationType, path)
        }
    }

    // --- Profile & E2EE Metadata ---

    suspend fun ensureUserProfile(
        displayName: String,
        email: String,
        photoUrl: String?
    ): Result<Unit> {
        var path = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid)
            path = ref.path
            val snap = ref.get().await()
            val cleanName = displayName.trim().take(120)
            val cleanEmail = email.trim().take(254)
            val cleanPhoto = photoUrl?.trim()?.take(500)?.ifBlank { null }

            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to cleanName,
                    "email" to cleanEmail,
                    "photoUrl" to cleanPhoto,
                    "e2eeEnabled" to false,
                    "storageUsedBytes" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                ref.set(payload).await()
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to cleanName,
                    "email" to cleanEmail,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanPhoto != null) updateMap["photoUrl"] = cleanPhoto
                ref.update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun saveE2eeSetup(saltBase64: String, verifier: String): Result<Unit> {
        var path = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid)
            path = ref.path
            ref.update(
                mapOf(
                    "e2eeEnabled" to true,
                    "e2eeSalt" to saltBase64.take(128),
                    "e2eeVerifier" to verifier.take(512),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateStorageUsedBytes(totalBytes: Long): Result<Unit> {
        var path = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid)
            path = ref.path
            ref.update(
                mapOf(
                    "storageUsedBytes" to totalBytes.coerceIn(0L, 10_737_418_240L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- Folders ---

    suspend fun createFolder(
        name: String,
        colorHex: String,
        iconName: String,
        privateVault: Boolean,
        tags: List<String>
    ): Result<String> {
        var path = "users/unauthenticated/folders"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Folder name cannot be empty." }
            val ref = userDoc(uid).collection("folders").document()
            path = ref.path
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "colorHex" to colorHex.trim().take(16).ifBlank { "#0D5C63" },
                "iconName" to iconName.trim().take(40).ifBlank { "folder" },
                "privateVault" to privateVault,
                "tags" to tags.map { it.trim().take(32) }.filter { it.isNotEmpty() }.distinct().take(20),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateFolder(
        folderId: String,
        name: String,
        colorHex: String,
        iconName: String,
        privateVault: Boolean,
        tags: List<String>
    ): Result<Unit> {
        var path = "users/unauthenticated/folders/$folderId"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            require(folderId.isNotBlank() && cleanName.isNotEmpty())
            val ref = userDoc(uid).collection("folders").document(folderId)
            path = ref.path
            ref.update(
                mapOf(
                    "name" to cleanName,
                    "colorHex" to colorHex.trim().take(16).ifBlank { "#0D5C63" },
                    "iconName" to iconName.trim().take(40).ifBlank { "folder" },
                    "privateVault" to privateVault,
                    "tags" to tags.map { it.trim().take(32) }.filter { it.isNotEmpty() }.distinct().take(20),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteFolder(folderId: String): Result<Unit> {
        var path = "users/unauthenticated/folders/$folderId"
        return try {
            val uid = requireUserId()
            require(folderId.isNotBlank())
            val ref = userDoc(uid).collection("folders").document(folderId)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Documents & Off-Device Cloud Blobs ---

    suspend fun createDocumentWithBlob(
        docId: String,
        document: VaultDocument,
        blob: DocumentBlob?
    ): Result<String> {
        var path = "users/unauthenticated/documents/$docId"
        return try {
            val uid = requireUserId()
            val docRef = userDoc(uid).collection("documents").document(docId)
            path = docRef.path

            val docPayload = buildMap<String, Any> {
                put("userId", uid)
                put("title", document.title.trim().take(200).ifBlank { "Untitled Document" })
                put("fileName", document.fileName.trim().take(255).ifBlank { "document.bin" })
                put("mimeType", document.mimeType.trim().take(120).ifBlank { "application/octet-stream" })
                put("fileCategory", document.fileCategory.takeIf {
                    it in setOf("IMAGE", "PDF", "DOCX", "ARCHIVE", "TEXT", "OTHER")
                } ?: "OTHER")
                put("fileSizeBytes", document.fileSizeBytes.coerceIn(0L, 524_288_000L))
                put("uploadDateMillis", if (document.uploadDateMillis > 0L) document.uploadDateMillis else System.currentTimeMillis())
                document.folderId?.takeIf { it.isNotBlank() }?.let { put("folderId", it.take(128)) }
                put("aiCategory", document.aiCategory.trim().take(80).ifBlank { "Uncategorized" })
                put("tags", document.tags.map { it.trim().take(32) }.filter { it.isNotEmpty() }.distinct().take(25))
                put("extractedText", document.extractedText.take(100_000))
                put("summary", document.summary.take(2_000))
                put("thumbnailBase64", document.thumbnailBase64.take(150_000))
                put("hasCloudBlob", blob != null)
                put("encrypted", document.encrypted)
                put("encryptedPayload", document.encryptedPayload.take(250_000))
                put("encryptionIv", document.encryptionIv.take(64))
                put("ocrStatus", document.ocrStatus.takeIf {
                    it in setOf("PENDING", "COMPLETED", "FAILED", "ENCRYPTED")
                } ?: "COMPLETED")
                put("pageCount", document.pageCount.coerceIn(0, 2000))
                put("createdAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }

            docRef.set(docPayload).await()

            if (blob != null && blob.dataBase64.length <= 900_000) {
                val blobRef = userDoc(uid).collection("doc_blobs").document(docId)
                path = blobRef.path
                val blobPayload = mapOf(
                    "userId" to uid,
                    "docId" to docId,
                    "mimeType" to blob.mimeType.trim().take(120).ifBlank { "application/octet-stream" },
                    "dataBase64" to blob.dataBase64,
                    "encrypted" to blob.encrypted,
                    "encryptionIv" to blob.encryptionIv.take(64),
                    "byteLength" to blob.byteLength.coerceIn(0L, 900_000L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                blobRef.set(blobPayload).await()
            }

            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDocumentDate(docId: String, newUploadDateMillis: Long): Result<Unit> {
        var path = "users/unauthenticated/documents/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank() && newUploadDateMillis > 0L)
            val ref = userDoc(uid).collection("documents").document(docId)
            path = ref.path
            ref.update(
                mapOf(
                    "uploadDateMillis" to newUploadDateMillis,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDocumentMetadata(
        docId: String,
        updates: Map<String, Any?>
    ): Result<Unit> {
        var path = "users/unauthenticated/documents/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank())
            val ref = userDoc(uid).collection("documents").document(docId)
            path = ref.path
            val sanitized = mutableMapOf<String, Any>()
            updates.forEach { (key, value) ->
                if (value != null) {
                    sanitized[key] = value
                } else if (key == "folderId") {
                    sanitized[key] = ""
                }
            }
            sanitized["updatedAt"] = FieldValue.serverTimestamp()
            ref.update(sanitized).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateDocumentBlob(docId: String, blob: DocumentBlob): Result<Unit> {
        var path = "users/unauthenticated/doc_blobs/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank() && blob.dataBase64.length <= 900_000)
            val ref = userDoc(uid).collection("doc_blobs").document(docId)
            path = ref.path
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "mimeType" to blob.mimeType.take(120),
                        "dataBase64" to blob.dataBase64,
                        "encrypted" to blob.encrypted,
                        "encryptionIv" to blob.encryptionIv.take(64),
                        "byteLength" to blob.byteLength.coerceIn(0L, 900_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "userId" to uid,
                        "docId" to docId,
                        "mimeType" to blob.mimeType.take(120),
                        "dataBase64" to blob.dataBase64,
                        "encrypted" to blob.encrypted,
                        "encryptionIv" to blob.encryptionIv.take(64),
                        "byteLength" to blob.byteLength.coerceIn(0L, 900_000L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun fetchDocumentBlob(docId: String): Result<DocumentBlob?> {
        var path = "users/unauthenticated/doc_blobs/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank())
            val ref = userDoc(uid).collection("doc_blobs").document(docId)
            path = ref.path
            val snap = ref.get().await()
            val blob = if (snap.exists()) {
                snap.toObject(DocumentBlob::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(blob)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDocument(docId: String): Result<Unit> {
        var path = "users/unauthenticated/documents/$docId"
        return try {
            val uid = requireUserId()
            require(docId.isNotBlank())
            val docRef = userDoc(uid).collection("documents").document(docId)
            path = docRef.path
            docRef.delete().await()

            val blobRef = userDoc(uid).collection("doc_blobs").document(docId)
            runCatching { blobRef.delete().await() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Saved Lookup Websites ---

    suspend fun saveWebsite(
        name: String,
        urlTemplate: String,
        category: String
    ): Result<String> {
        var path = "users/unauthenticated/saved_websites"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            val cleanUrl = normalizeWebsiteUrl(urlTemplate)
            require(cleanName.isNotEmpty()) { "Website name is required." }
            require(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://")) {
                "Website URL must start with https:// or http://"
            }
            val ref = userDoc(uid).collection("saved_websites").document()
            path = ref.path
            ref.set(
                mapOf(
                    "userId" to uid,
                    "name" to cleanName,
                    "urlTemplate" to cleanUrl.take(500),
                    "category" to category.trim().take(50).ifBlank { "Custom" },
                    "usageCount" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun incrementWebsiteUsage(siteId: String): Result<Unit> {
        var path = "users/unauthenticated/saved_websites/$siteId"
        return try {
            val uid = requireUserId()
            require(siteId.isNotBlank())
            val ref = userDoc(uid).collection("saved_websites").document(siteId)
            path = ref.path
            ref.update(
                mapOf(
                    "usageCount" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteWebsite(siteId: String): Result<Unit> {
        var path = "users/unauthenticated/saved_websites/$siteId"
        return try {
            val uid = requireUserId()
            require(siteId.isNotBlank())
            val ref = userDoc(uid).collection("saved_websites").document(siteId)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Search & AI History ---

    suspend fun addSearchHistory(
        query: String,
        mode: String,
        aiAnswerPreview: String = "",
        resultCount: Long = 0L
    ): Result<Unit> {
        var path = "users/unauthenticated/search_history"
        return try {
            val uid = requireUserId()
            val cleanQuery = query.trim().take(500)
            if (cleanQuery.isEmpty()) return Result.success(Unit)
            val safeMode = mode.takeIf { it in setOf("SEARCH", "AI_ASK", "WEB_LOOKUP") } ?: "SEARCH"
            val ref = userDoc(uid).collection("search_history").document()
            path = ref.path
            ref.set(
                mapOf(
                    "userId" to uid,
                    "query" to cleanQuery,
                    "mode" to safeMode,
                    "aiAnswerPreview" to aiAnswerPreview.trim().take(2000),
                    "resultCount" to resultCount.coerceIn(0L, 100_000L),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteSearchHistoryItem(historyId: String): Result<Unit> {
        var path = "users/unauthenticated/search_history/$historyId"
        return try {
            val uid = requireUserId()
            require(historyId.isNotBlank())
            val ref = userDoc(uid).collection("search_history").document(historyId)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun clearSearchHistory(historyIds: List<String>): Result<Unit> {
        var path = "users/unauthenticated/search_history"
        return try {
            val uid = requireUserId()
            val col = userDoc(uid).collection("search_history")
            path = col.path
            historyIds.chunked(200).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { id -> batch.delete(col.document(id)) }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    companion object {
        fun normalizeWebsiteUrl(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }
    }
}
