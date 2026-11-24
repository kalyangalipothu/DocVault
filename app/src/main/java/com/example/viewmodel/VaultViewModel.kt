package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.DarkModeOption
import com.example.core.DocumentProcessor
import com.example.core.E2eeDocumentSecret
import com.example.core.FileCategory
import com.example.core.PreferencesRepository
import com.example.core.SearchFilter
import com.example.core.SearchHit
import com.example.core.SearchIndexEngine
import com.example.core.UserAppPreferences
import com.example.core.VaultCryptoManager
import com.example.data.DateFilterPreset
import com.example.data.DecryptedDocumentView
import com.example.data.DocumentBlob
import com.example.data.GeminiAiService
import com.example.data.SavedWebsite
import com.example.data.SearchHistoryEntry
import com.example.data.UiState
import com.example.data.UserProfile
import com.example.data.VaultDocument
import com.example.data.VaultFolder
import com.example.data.VaultRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.crypto.SecretKey

private const val TAG = "VaultViewModel"

sealed interface AiSearchUiState {
    data object Idle : AiSearchUiState
    data class Streaming(
        val question: String,
        val partialAnswer: String,
        val sources: List<DecryptedDocumentView>
    ) : AiSearchUiState
    data class Success(
        val question: String,
        val answer: String,
        val sources: List<DecryptedDocumentView>
    ) : AiSearchUiState
    data class Error(val question: String, val message: String) : AiSearchUiState
}

data class UploadProgressState(
    val active: Boolean = false,
    val statusMessage: String = "",
    val processedCount: Int = 0,
    val totalCount: Int = 0
)

data class VaultDashboardState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val profile: UserProfile? = null,
    val folders: List<VaultFolder> = emptyList(),
    val allDocuments: List<DecryptedDocumentView> = emptyList(),
    val filteredHits: List<SearchHit> = emptyList(),
    val savedWebsites: List<SavedWebsite> = emptyList(),
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val filter: SearchFilter = SearchFilter(),
    val datePreset: DateFilterPreset = DateFilterPreset.ALL,
    val aiSearchMode: Boolean = false,
    val aiSearchState: AiSearchUiState = AiSearchUiState.Idle,
    val e2eeUnlocked: Boolean = false,
    val e2eeFingerprint: String? = null,
    val uploadProgress: UploadProgressState = UploadProgressState(),
    val isBatchOrganizing: Boolean = false,
    val snackbarMessage: String? = null
)

class VaultViewModel(
    private val repository: VaultRepository,
    private val aiService: GeminiAiService,
    private val preferencesRepository: PreferencesRepository,
    private val currentUserId: String
) : ViewModel() {

    private val searchEngine = SearchIndexEngine()
    private val _vaultSecretKey = MutableStateFlow<SecretKey?>(null)

    private val _filter = MutableStateFlow(SearchFilter())
    private val _datePreset = MutableStateFlow(DateFilterPreset.ALL)
    private val _aiSearchMode = MutableStateFlow(false)
    private val _aiSearchState = MutableStateFlow<AiSearchUiState>(AiSearchUiState.Idle)
    private val _uploadProgress = MutableStateFlow(UploadProgressState())
    private val _isBatchOrganizing = MutableStateFlow(false)
    private val _snackbarMessage = MutableStateFlow<String?>(null)

    private val _loadedFilesCache = MutableStateFlow<Map<String, File>>(emptyMap())
    val loadedFilesCache: StateFlow<Map<String, File>> = _loadedFilesCache.asStateFlow()

    private val _loadingBlobDocId = MutableStateFlow<String?>(null)
    val loadingBlobDocId: StateFlow<String?> = _loadingBlobDocId.asStateFlow()

    private var aiJob: Job? = null

    val preferences: StateFlow<UserAppPreferences> = preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserAppPreferences())

    private val profileFlow: StateFlow<UiState<UserProfile?>> = repository.observeProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { emit(handleFlowFailure(it, "Failed to sync profile")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val foldersFlow: StateFlow<UiState<List<VaultFolder>>> = repository.observeFolders(currentUserId)
        .map<List<VaultFolder>, UiState<List<VaultFolder>>> { UiState.Success(it) }
        .catch { emit(handleFlowFailure(it, "Failed to sync folders")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val documentsFlow: StateFlow<UiState<List<VaultDocument>>> = repository.observeDocuments(currentUserId)
        .map<List<VaultDocument>, UiState<List<VaultDocument>>> { UiState.Success(it) }
        .catch { emit(handleFlowFailure(it, "Failed to sync documents")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val websitesFlow: StateFlow<UiState<List<SavedWebsite>>> = repository.observeSavedWebsites(currentUserId)
        .map<List<SavedWebsite>, UiState<List<SavedWebsite>>> { UiState.Success(it) }
        .catch { emit(handleFlowFailure(it, "Failed to sync saved websites")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val historyFlow: StateFlow<UiState<List<SearchHistoryEntry>>> = repository.observeSearchHistory(currentUserId)
        .map<List<SearchHistoryEntry>, UiState<List<SearchHistoryEntry>>> { UiState.Success(it) }
        .catch { emit(handleFlowFailure(it, "Failed to sync search history")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val decryptedDocsFlow: StateFlow<Pair<UiState<List<DecryptedDocumentView>>, Boolean>> =
        combine(documentsFlow, _vaultSecretKey) { docsState, key ->
            when (docsState) {
                is UiState.Loading -> UiState.Loading to (key != null)
                is UiState.Error -> UiState.Error(docsState.message) to (key != null)
                is UiState.Success -> {
                    val decrypted = withContext(Dispatchers.Default) {
                        val list = docsState.data.map { doc -> decryptDocumentView(doc, key) }
                        searchEngine.rebuild(list)
                        list
                    }
                    UiState.Success(decrypted) to (key != null)
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            UiState.Loading to false
        )

    private val filteredHitsFlow: StateFlow<List<SearchHit>> =
        combine(decryptedDocsFlow, _filter) { (docsState, _), filter ->
            if (docsState is UiState.Success) {
                withContext(Dispatchers.Default) {
                    searchEngine.search(filter)
                }
            } else {
                emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val coreDataFlow = combine(
        profileFlow,
        foldersFlow,
        decryptedDocsFlow,
        websitesFlow,
        historyFlow
    ) { profileState, foldersState, docsPair, websitesState, historyState ->
        CoreDataBundle(profileState, foldersState, docsPair.first, docsPair.second, websitesState, historyState)
    }

    private val uiInteractionFlow = combine(
        _filter,
        _datePreset,
        _aiSearchMode,
        _aiSearchState,
        _uploadProgress
    ) { filter, datePreset, aiMode, aiState, upload ->
        InteractionBundle(filter, datePreset, aiMode, aiState, upload)
    }

    val uiState: StateFlow<VaultDashboardState> = combine(
        coreDataFlow,
        filteredHitsFlow,
        uiInteractionFlow,
        _isBatchOrganizing,
        _snackbarMessage
    ) { core, hits, interaction, batchOrg, snackbar ->
        val profile = (core.profile as? UiState.Success)?.data
        val folders = (core.folders as? UiState.Success)?.data ?: emptyList()
        val docs = (core.docs as? UiState.Success)?.data ?: emptyList()
        val websites = (core.websites as? UiState.Success)?.data ?: emptyList()
        val history = (core.history as? UiState.Success)?.data ?: emptyList()
        val errorMsg = (core.docs as? UiState.Error)?.message
            ?: (core.folders as? UiState.Error)?.message
            ?: (core.profile as? UiState.Error)?.message
        val isLoading = core.docs is UiState.Loading && errorMsg == null
        val key = _vaultSecretKey.value

        VaultDashboardState(
            isLoading = isLoading,
            errorMessage = errorMsg,
            profile = profile,
            folders = folders,
            allDocuments = docs,
            filteredHits = hits,
            savedWebsites = websites,
            searchHistory = history,
            filter = interaction.filter,
            datePreset = interaction.datePreset,
            aiSearchMode = interaction.aiMode,
            aiSearchState = interaction.aiState,
            e2eeUnlocked = core.e2eeUnlocked,
            e2eeFingerprint = key?.let { VaultCryptoManager.keyFingerprint(it) },
            uploadProgress = interaction.upload,
            isBatchOrganizing = batchOrg,
            snackbarMessage = snackbar
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), VaultDashboardState())

    init {
        syncUserProfileOnStart()
    }

    private fun syncUserProfileOnStart() {
        viewModelScope.launch {
            val user = Firebase.auth.currentUser ?: return@launch
            repository.ensureUserProfile(
                displayName = user.displayName ?: "DocVault User",
                email = user.email ?: "",
                photoUrl = user.photoUrl?.toString()
            )
        }
    }

    private fun handleFlowFailure(error: Throwable, fallback: String): UiState.Error {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, fallback, root)
        return UiState.Error(root.message ?: fallback)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    // --- Preferences & Appearance ---

    fun setDarkMode(option: DarkModeOption) {
        viewModelScope.launch { preferencesRepository.setDarkMode(option) }
    }

    fun setBiometricAppLock(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setBiometricAppLock(enabled) }
    }

    fun toggleSortOrder() {
        val next = !_filter.value.sortDescending
        _filter.value = _filter.value.copy(sortDescending = next)
        viewModelScope.launch { preferencesRepository.setSortDescending(next) }
    }

    fun setGridView(grid: Boolean) {
        viewModelScope.launch { preferencesRepository.setGridView(grid) }
    }

    // --- Search, Filtering & History ---

    fun updateSearchQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
        if (query.isBlank() && _aiSearchState.value !is AiSearchUiState.Idle) {
            _aiSearchState.value = AiSearchUiState.Idle
        }
    }

    fun commitKeywordSearchToHistory(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        val count = uiState.value.filteredHits.size.toLong()
        viewModelScope.launch {
            repository.addSearchHistory(query = clean, mode = "SEARCH", resultCount = count)
        }
    }

    fun toggleAiSearchMode(enabled: Boolean) {
        _aiSearchMode.value = enabled
        if (!enabled) {
            aiJob?.cancel()
            _aiSearchState.value = AiSearchUiState.Idle
        }
    }

    fun askAiInSearchBar(question: String) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        _filter.value = _filter.value.copy(query = clean)
        _aiSearchMode.value = true
        aiJob?.cancel()

        aiJob = viewModelScope.launch {
            val sources = withContext(Dispatchers.Default) {
                searchEngine.topRelevantForAi(clean, maxDocs = 8)
            }
            _aiSearchState.value = AiSearchUiState.Streaming(clean, "", sources)
            try {
                var finalAnswer = ""
                aiService.askDocumentsStream(clean, sources).collect { partial ->
                    finalAnswer = partial
                    _aiSearchState.value = AiSearchUiState.Streaming(clean, partial, sources)
                }
                _aiSearchState.value = AiSearchUiState.Success(clean, finalAnswer, sources)
                repository.addSearchHistory(
                    query = clean,
                    mode = "AI_ASK",
                    aiAnswerPreview = finalAnswer.take(400),
                    resultCount = sources.size.toLong()
                )
            } catch (e: TimeoutCancellationException) {
                _aiSearchState.value = AiSearchUiState.Error(clean, "AI search timed out after 60 seconds. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI Search Bar failed", e)
                _aiSearchState.value = AiSearchUiState.Error(
                    clean,
                    e.localizedMessage ?: "Unable to query AI assistant right now."
                )
            }
        }
    }

    fun clearAiAnswer() {
        aiJob?.cancel()
        _aiSearchState.value = AiSearchUiState.Idle
    }

    fun selectFolderFilter(folderId: String?, onlyPrivateVault: Boolean = false) {
        _filter.value = _filter.value.copy(
            folderId = folderId,
            onlyPrivateVault = onlyPrivateVault,
            aiCategory = null
        )
    }

    fun selectAiCategoryFilter(category: String?) {
        _filter.value = _filter.value.copy(
            aiCategory = if (_filter.value.aiCategory == category) null else category
        )
    }

    fun selectFileCategoryFilter(fileCat: String?) {
        _filter.value = _filter.value.copy(
            fileCategory = if (_filter.value.fileCategory == fileCat) null else fileCat
        )
    }

    fun selectTagFilter(tag: String?) {
        _filter.value = _filter.value.copy(
            tag = if (_filter.value.tag == tag) null else tag
        )
    }

    fun applyDatePreset(preset: DateFilterPreset) {
        _datePreset.value = preset
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val range: Pair<Long?, Long?> = when (preset) {
            DateFilterPreset.ALL -> null to null
            DateFilterPreset.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            DateFilterPreset.LAST_7_DAYS -> (now - 7L * 86_400_000L) to now
            DateFilterPreset.LAST_30_DAYS -> (now - 30L * 86_400_000L) to now
            DateFilterPreset.THIS_YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis to now
            }
            DateFilterPreset.CUSTOM -> _filter.value.startDateMillis to _filter.value.endDateMillis
        }
        _filter.value = _filter.value.copy(startDateMillis = range.first, endDateMillis = range.second)
    }

    fun applyCustomDateRange(startMillis: Long?, endMillis: Long?) {
        _datePreset.value = if (startMillis == null && endMillis == null) {
            DateFilterPreset.ALL
        } else {
            DateFilterPreset.CUSTOM
        }
        val inclusiveEnd = endMillis?.let { it + 86_399_999L }
        _filter.value = _filter.value.copy(startDateMillis = startMillis, endDateMillis = inclusiveEnd)
    }

    fun clearAllFilters() {
        _datePreset.value = DateFilterPreset.ALL
        _filter.value = SearchFilter(sortDescending = _filter.value.sortDescending)
        clearAiAnswer()
    }

    fun deleteHistoryItem(id: String) {
        viewModelScope.launch { repository.deleteSearchHistoryItem(id) }
    }

    fun clearAllHistory() {
        val ids = uiState.value.searchHistory.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.clearSearchHistory(ids)
            _snackbarMessage.value = "Search history cleared."
        }
    }

    // --- End-to-End Encryption (Private Vault Folders) ---

    fun setupE2eeVault(passphrase: String, saveForBiometrics: Boolean, onResult: (Boolean, String) -> Unit) {
        if (passphrase.length < 6) {
            onResult(false, "Passphrase must be at least 6 characters.")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val salt = VaultCryptoManager.generateSaltBase64()
                val key = VaultCryptoManager.deriveKey(passphrase.toCharArray(), salt)
                val verifier = VaultCryptoManager.createVerifier(key)
                Triple(salt, key, verifier)
            }
            val (salt, key, verifier) = result
            val saveRes = repository.saveE2eeSetup(salt, verifier)
            if (saveRes.isSuccess) {
                _vaultSecretKey.value = key
                if (saveForBiometrics) {
                    VaultCryptoManager.wrapKeyForDevice(key)?.let { wrapped ->
                        preferencesRepository.saveWrappedVaultKey(wrapped.ciphertextBase64, wrapped.ivBase64)
                    }
                }
                _snackbarMessage.value = "End-to-end encryption enabled for Private Vault."
                onResult(true, "E2EE Private Vault configured.")
            } else {
                onResult(false, saveRes.exceptionOrNull()?.localizedMessage ?: "Failed to save vault encryption setup.")
            }
        }
    }

    fun unlockE2eeWithPassphrase(passphrase: String, saveForBiometrics: Boolean, onResult: (Boolean, String) -> Unit) {
        val profile = uiState.value.profile
        val salt = profile?.e2eeSalt
        val verifier = profile?.e2eeVerifier
        if (salt.isNullOrBlank() || verifier.isNullOrBlank()) {
            onResult(false, "Private Vault is not set up yet.")
            return
        }
        viewModelScope.launch {
            val key = withContext(Dispatchers.Default) {
                val derived = VaultCryptoManager.deriveKey(passphrase.toCharArray(), salt)
                if (VaultCryptoManager.verifyKey(derived, verifier)) derived else null
            }
            if (key != null) {
                _vaultSecretKey.value = key
                if (saveForBiometrics) {
                    VaultCryptoManager.wrapKeyForDevice(key)?.let { wrapped ->
                        preferencesRepository.saveWrappedVaultKey(wrapped.ciphertextBase64, wrapped.ivBase64)
                    }
                }
                _snackbarMessage.value = "Private Vault unlocked."
                onResult(true, "Unlocked")
            } else {
                onResult(false, "Incorrect Vault Passphrase.")
            }
        }
    }

    fun unlockE2eeFromBiometricWrap(): Boolean {
        val prefs = preferences.value
        val profile = uiState.value.profile
        val verifier = profile?.e2eeVerifier
        if (prefs.wrappedVaultKey.isBlank() || prefs.wrappedVaultKeyIv.isBlank() || verifier.isNullOrBlank()) {
            return false
        }
        val key = VaultCryptoManager.unwrapKeyFromDevice(prefs.wrappedVaultKey, prefs.wrappedVaultKeyIv)
            ?: return false
        if (!VaultCryptoManager.verifyKey(key, verifier)) return false
        _vaultSecretKey.value = key
        _snackbarMessage.value = "Private Vault unlocked with biometrics."
        return true
    }

    fun hasBiometricWrappedVaultKey(): Boolean {
        val prefs = preferences.value
        return prefs.wrappedVaultKey.isNotBlank() && prefs.wrappedVaultKeyIv.isNotBlank()
    }

    fun lockE2eeVault() {
        _vaultSecretKey.value = null
        _snackbarMessage.value = "Private Vault locked."
    }

    // --- Document Upload Pipeline (Camera / Photo Picker / Device Files) ---

    fun uploadDocuments(
        context: Context,
        uris: List<Uri>,
        targetFolderId: String?,
        uploadDateMillis: Long = System.currentTimeMillis()
    ) {
        if (uris.isEmpty()) return
        val folder = uiState.value.folders.firstOrNull { it.id == targetFolderId }
        val encryptPrivate = folder?.privateVault == true
        val activeKey = _vaultSecretKey.value

        if (encryptPrivate && activeKey == null) {
            _snackbarMessage.value = "Unlock your Private Vault before uploading into an encrypted folder."
            return
        }

        viewModelScope.launch {
            _uploadProgress.value = UploadProgressState(
                active = true,
                statusMessage = "Preparing ${uris.size} file(s)…",
                processedCount = 0,
                totalCount = uris.size
            )

            var successCount = 0
            var totalNewCloudBytes = 0L

            uris.forEachIndexed { idx, uri ->
                val docId = repository.newDocumentId(currentUserId)
                try {
                    _uploadProgress.value = UploadProgressState(
                        active = true,
                        statusMessage = "Reading file ${idx + 1} of ${uris.size}…",
                        processedCount = idx,
                        totalCount = uris.size
                    )

                    val processed = DocumentProcessor.processUri(
                        context = context.applicationContext,
                        uri = uri,
                        docId = docId,
                        fallbackName = "scan_${System.currentTimeMillis()}.jpg"
                    )

                    _uploadProgress.value = UploadProgressState(
                        active = true,
                        statusMessage = "AI OCR & categorizing '${processed.fileName}'…",
                        processedCount = idx,
                        totalCount = uris.size
                    )

                    val aiResult = when {
                        processed.ocrBitmaps.isNotEmpty() -> {
                            aiService.analyzeDocumentBitmaps(
                                bitmaps = processed.ocrBitmaps,
                                fileName = processed.fileName,
                                fileCategory = processed.fileCategory.name
                            )
                        }
                        processed.localTextExtraction.isNotBlank() -> {
                            aiService.analyzeExtractedText(
                                rawText = processed.localTextExtraction,
                                fileName = processed.fileName,
                                fileCategory = processed.fileCategory.name
                            )
                        }
                        else -> Result.failure(IllegalStateException("No readable content."))
                    }

                    // Recycle OCR bitmaps promptly to keep memory footprint low
                    processed.ocrBitmaps.forEach { if (!it.isRecycled) it.recycle() }

                    val analysis = aiResult.getOrNull()
                    val title = analysis?.title ?: processed.fileName.substringBeforeLast('.').ifBlank { processed.fileName }
                    val extractedText = analysis?.extractedText?.ifBlank { processed.localTextExtraction }
                        ?: processed.localTextExtraction
                    val summary = analysis?.summary ?: ""
                    val aiCategory = analysis?.category ?: defaultCategoryForFile(processed.fileCategory)
                    val mergedTags = ((analysis?.tags ?: emptyList()) + (folder?.tags ?: emptyList()))
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(20)
                    val ocrStatus = when {
                        encryptPrivate -> "ENCRYPTED"
                        analysis != null -> "COMPLETED"
                        extractedText.isNotBlank() -> "COMPLETED"
                        else -> "FAILED"
                    }

                    _uploadProgress.value = UploadProgressState(
                        active = true,
                        statusMessage = if (encryptPrivate) "Encrypting & syncing '${processed.fileName}'…"
                        else "Syncing '${processed.fileName}' to cloud vault…",
                        processedCount = idx,
                        totalCount = uris.size
                    )

                    val vaultDoc: VaultDocument
                    val cloudBlob: DocumentBlob?

                    if (encryptPrivate && activeKey != null) {
                        val secret = E2eeDocumentSecret(
                            title = title,
                            summary = summary,
                            extractedText = extractedText,
                            thumbnailBase64 = processed.thumbnailBase64
                        )
                        val metaEnv = withContext(Dispatchers.Default) {
                            VaultCryptoManager.encryptDocumentSecret(secret, activeKey)
                        }

                        vaultDoc = VaultDocument(
                            id = docId,
                            userId = currentUserId,
                            title = "Encrypted Document",
                            fileName = processed.fileName,
                            mimeType = processed.mimeType,
                            fileCategory = processed.fileCategory.name,
                            fileSizeBytes = processed.fileSizeBytes,
                            uploadDateMillis = uploadDateMillis,
                            folderId = targetFolderId,
                            aiCategory = aiCategory,
                            tags = mergedTags,
                            extractedText = "",
                            summary = "",
                            thumbnailBase64 = "",
                            hasCloudBlob = processed.cloudBlobBytes != null,
                            encrypted = true,
                            encryptedPayload = metaEnv.ciphertextBase64,
                            encryptionIv = metaEnv.ivBase64,
                            ocrStatus = "ENCRYPTED",
                            pageCount = processed.pageCount
                        )

                        cloudBlob = processed.cloudBlobBytes?.let { rawBytes ->
                            val blobEnv = withContext(Dispatchers.Default) {
                                VaultCryptoManager.encryptBytes(rawBytes, activeKey)
                            }
                            if (blobEnv.ciphertextBase64.length <= 880_000) {
                                totalNewCloudBytes += rawBytes.size.toLong()
                                DocumentBlob(
                                    userId = currentUserId,
                                    docId = docId,
                                    mimeType = processed.cloudBlobMimeType,
                                    dataBase64 = blobEnv.ciphertextBase64,
                                    encrypted = true,
                                    encryptionIv = blobEnv.ivBase64,
                                    byteLength = rawBytes.size.toLong()
                                )
                            } else null
                        }
                    } else {
                        vaultDoc = VaultDocument(
                            id = docId,
                            userId = currentUserId,
                            title = title,
                            fileName = processed.fileName,
                            mimeType = processed.mimeType,
                            fileCategory = processed.fileCategory.name,
                            fileSizeBytes = processed.fileSizeBytes,
                            uploadDateMillis = uploadDateMillis,
                            folderId = targetFolderId,
                            aiCategory = aiCategory,
                            tags = mergedTags,
                            extractedText = extractedText,
                            summary = summary,
                            thumbnailBase64 = processed.thumbnailBase64,
                            hasCloudBlob = processed.cloudBlobBytes != null,
                            encrypted = false,
                            encryptedPayload = "",
                            encryptionIv = "",
                            ocrStatus = ocrStatus,
                            pageCount = processed.pageCount
                        )

                        cloudBlob = processed.cloudBlobBytes?.let { rawBytes ->
                            val b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                            if (b64.length <= 880_000) {
                                totalNewCloudBytes += rawBytes.size.toLong()
                                DocumentBlob(
                                    userId = currentUserId,
                                    docId = docId,
                                    mimeType = processed.cloudBlobMimeType,
                                    dataBase64 = b64,
                                    encrypted = false,
                                    encryptionIv = "",
                                    byteLength = rawBytes.size.toLong()
                                )
                            } else null
                        }
                    }

                    val saveRes = repository.createDocumentWithBlob(docId, vaultDoc, cloudBlob)
                    if (saveRes.isSuccess) {
                        successCount++
                        processed.cachedLocalFile?.let { f ->
                            _loadedFilesCache.value = _loadedFilesCache.value + (docId to f)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed uploading document $uri", e)
                    _snackbarMessage.value = "Upload failed: ${e.localizedMessage ?: "Unsupported file"}"
                }
            }

            if (totalNewCloudBytes > 0L) {
                val existingBytes = uiState.value.profile?.storageUsedBytes ?: 0L
                repository.updateStorageUsedBytes(existingBytes + totalNewCloudBytes)
            }

            _uploadProgress.value = UploadProgressState(active = false)
            if (successCount > 0) {
                _snackbarMessage.value = if (successCount == 1) {
                    "Document scanned, categorized & synced to cloud."
                } else {
                    "$successCount documents scanned, categorized & synced."
                }
            }
        }
    }

    // --- Document Editing, Upload Date Change, Folder Move (with E2EE re-encryption), & Rescan ---

    fun updateDocumentUploadDate(docId: String, newDateMillis: Long) {
        viewModelScope.launch {
            val res = repository.updateDocumentDate(docId, newDateMillis)
            _snackbarMessage.value = if (res.isSuccess) {
                "Upload date updated."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to update upload date."
            }
        }
    }

    fun updateDocumentDetails(
        docView: DecryptedDocumentView,
        newTitle: String,
        newCategory: String,
        newTags: List<String>,
        newFolderId: String?
    ) {
        val targetFolder = uiState.value.folders.firstOrNull { it.id == newFolderId }
        val shouldEncrypt = targetFolder?.privateVault == true
        val activeKey = _vaultSecretKey.value

        if ((shouldEncrypt || docView.raw.encrypted) && activeKey == null) {
            _snackbarMessage.value = "Unlock your Private Vault to edit or move encrypted documents."
            return
        }

        viewModelScope.launch {
            val cleanTitle = newTitle.trim().take(200).ifBlank { docView.displayTitle }
            val cleanTags = newTags.map { it.trim().lowercase().take(32) }.filter { it.isNotEmpty() }.distinct().take(25)

            val updates: Map<String, Any?> = if (shouldEncrypt && activeKey != null) {
                val secret = E2eeDocumentSecret(
                    title = cleanTitle,
                    summary = docView.displaySummary,
                    extractedText = docView.displayExtractedText,
                    thumbnailBase64 = docView.displayThumbnailBase64
                )
                val env = withContext(Dispatchers.Default) {
                    VaultCryptoManager.encryptDocumentSecret(secret, activeKey)
                }
                mapOf(
                    "title" to "Encrypted Document",
                    "folderId" to newFolderId,
                    "aiCategory" to newCategory.take(80),
                    "tags" to cleanTags,
                    "extractedText" to "",
                    "summary" to "",
                    "thumbnailBase64" to "",
                    "encrypted" to true,
                    "encryptedPayload" to env.ciphertextBase64,
                    "encryptionIv" to env.ivBase64,
                    "ocrStatus" to "ENCRYPTED"
                )
            } else {
                mapOf(
                    "title" to cleanTitle,
                    "folderId" to newFolderId,
                    "aiCategory" to newCategory.take(80),
                    "tags" to cleanTags,
                    "extractedText" to docView.displayExtractedText.take(100_000),
                    "summary" to docView.displaySummary.take(2_000),
                    "thumbnailBase64" to docView.displayThumbnailBase64.take(150_000),
                    "encrypted" to false,
                    "encryptedPayload" to "",
                    "encryptionIv" to "",
                    "ocrStatus" to "COMPLETED"
                )
            }

            // Re-encrypt or decrypt off-device cloud blob if privacy state toggled
            if (shouldEncrypt != docView.raw.encrypted && docView.raw.hasCloudBlob && activeKey != null) {
                val blobRes = repository.fetchDocumentBlob(docView.raw.id)
                val existingBlob = blobRes.getOrNull()
                if (existingBlob != null && existingBlob.dataBase64.isNotBlank()) {
                    val plainBytes = withContext(Dispatchers.Default) {
                        if (existingBlob.encrypted) {
                            VaultCryptoManager.decryptBytes(
                                existingBlob.dataBase64,
                                existingBlob.encryptionIv,
                                activeKey
                            )
                        } else {
                            Base64.decode(existingBlob.dataBase64, Base64.NO_WRAP)
                        }
                    }
                    val updatedBlob = if (shouldEncrypt) {
                        val enc = withContext(Dispatchers.Default) {
                            VaultCryptoManager.encryptBytes(plainBytes, activeKey)
                        }
                        existingBlob.copy(
                            dataBase64 = enc.ciphertextBase64,
                            encrypted = true,
                            encryptionIv = enc.ivBase64
                        )
                    } else {
                        existingBlob.copy(
                            dataBase64 = Base64.encodeToString(plainBytes, Base64.NO_WRAP),
                            encrypted = false,
                            encryptionIv = ""
                        )
                    }
                    repository.updateDocumentBlob(docView.raw.id, updatedBlob)
                }
            }

            val res = repository.updateDocumentMetadata(docView.raw.id, updates)
            _snackbarMessage.value = if (res.isSuccess) {
                if (shouldEncrypt && !docView.raw.encrypted) "Moved & encrypted into Private Vault."
                else "Document updated."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to update document."
            }
        }
    }

    fun deleteDocument(context: Context, doc: VaultDocument) {
        viewModelScope.launch {
            val res = repository.deleteDocument(doc.id)
            if (res.isSuccess) {
                DocumentProcessor.getLocalVaultFile(context, doc.id)?.delete()
                _loadedFilesCache.value = _loadedFilesCache.value - doc.id
                _snackbarMessage.value = "Document deleted from vault and cloud."
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete document."
            }
        }
    }

    fun rescanDocumentWithAi(context: Context, docView: DecryptedDocumentView) {
        if (docView.isLocked) {
            _snackbarMessage.value = "Unlock Private Vault to scan this document."
            return
        }
        viewModelScope.launch {
            _snackbarMessage.value = "Running AI OCR & smart categorization…"
            val localFile = ensureLocalFileReadySync(context, docView.raw)
            val analysisRes = if (localFile != null && docView.raw.fileCategory == FileCategory.IMAGE.name) {
                val bmp = withContext(Dispatchers.IO) { DocumentProcessor.decodeSampledBitmap(localFile, 1280) }
                if (bmp != null) {
                    val r = aiService.analyzeDocumentBitmaps(listOf(bmp), docView.raw.fileName, "IMAGE")
                    bmp.recycle()
                    r
                } else {
                    aiService.analyzeExtractedText(docView.displayExtractedText, docView.raw.fileName, docView.raw.fileCategory)
                }
            } else if (localFile != null && docView.raw.fileCategory == FileCategory.PDF.name) {
                val rendered = withContext(Dispatchers.IO) { DocumentProcessor.renderPdfPage(localFile, 0, 1280) }
                if (rendered != null) {
                    val r = aiService.analyzeDocumentBitmaps(listOf(rendered.first), docView.raw.fileName, "PDF")
                    rendered.first.recycle()
                    r
                } else {
                    aiService.analyzeExtractedText(docView.displayExtractedText, docView.raw.fileName, "PDF")
                }
            } else {
                aiService.analyzeExtractedText(docView.displayExtractedText, docView.raw.fileName, docView.raw.fileCategory)
            }

            val analysis = analysisRes.getOrNull()
            if (analysis == null) {
                _snackbarMessage.value = analysisRes.exceptionOrNull()?.localizedMessage ?: "AI scan failed."
                return@launch
            }

            val updatedDocView = docView.copy(
                displayTitle = analysis.title,
                displaySummary = analysis.summary,
                displayExtractedText = analysis.extractedText
            )
            updateDocumentDetails(
                docView = updatedDocView,
                newTitle = analysis.title,
                newCategory = analysis.category,
                newTags = (docView.raw.tags + analysis.tags).distinct().take(15),
                newFolderId = docView.raw.folderId
            )
        }
    }

    fun autoCategorizeAllUncategorized() {
        val candidates = uiState.value.allDocuments.filter {
            !it.isLocked && (it.raw.aiCategory == "Uncategorized" || it.raw.summary.isBlank()) && it.displayExtractedText.isNotBlank()
        }
        if (candidates.isEmpty()) {
            _snackbarMessage.value = "All unlocked documents are already categorized by AI."
            return
        }
        viewModelScope.launch {
            _isBatchOrganizing.value = true
            var updated = 0
            for (docView in candidates.take(15)) {
                val res = aiService.analyzeExtractedText(
                    docView.displayExtractedText,
                    docView.raw.fileName,
                    docView.raw.fileCategory
                )
                val analysis = res.getOrNull()
                if (analysis != null) {
                    updateDocumentDetails(
                        docView = docView.copy(displaySummary = analysis.summary),
                        newTitle = docView.displayTitle,
                        newCategory = analysis.category,
                        newTags = (docView.raw.tags + analysis.tags).distinct().take(15),
                        newFolderId = docView.raw.folderId
                    )
                    updated++
                }
            }
            _isBatchOrganizing.value = false
            _snackbarMessage.value = "AI categorized $updated document(s)."
        }
    }

    // --- Lazy Off-Device Cloud Blob Loader ---

    fun ensureDocumentFileLoaded(context: Context, doc: VaultDocument) {
        if (_loadedFilesCache.value.containsKey(doc.id)) return
        val existingLocal = DocumentProcessor.getLocalVaultFile(context, doc.id)
        if (existingLocal != null && existingLocal.exists()) {
            _loadedFilesCache.value = _loadedFilesCache.value + (doc.id to existingLocal)
            return
        }
        if (!doc.hasCloudBlob) return
        if (doc.encrypted && _vaultSecretKey.value == null) return

        viewModelScope.launch {
            _loadingBlobDocId.value = doc.id
            try {
                ensureLocalFileReadySync(context, doc)
            } finally {
                if (_loadingBlobDocId.value == doc.id) {
                    _loadingBlobDocId.value = null
                }
            }
        }
    }

    private suspend fun ensureLocalFileReadySync(context: Context, doc: VaultDocument): File? {
        _loadedFilesCache.value[doc.id]?.let { if (it.exists()) return it }
        DocumentProcessor.getLocalVaultFile(context, doc.id)?.let {
            if (it.exists()) {
                _loadedFilesCache.value = _loadedFilesCache.value + (doc.id to it)
                return it
            }
        }
        if (!doc.hasCloudBlob) return null
        val blob = repository.fetchDocumentBlob(doc.id).getOrNull() ?: return null
        if (blob.dataBase64.isBlank()) return null

        val key = _vaultSecretKey.value
        val bytes = withContext(Dispatchers.Default) {
            if (blob.encrypted) {
                if (key == null) return@withContext null
                VaultCryptoManager.decryptBytes(blob.dataBase64, blob.encryptionIv, key)
            } else {
                Base64.decode(blob.dataBase64, Base64.NO_WRAP)
            }
        } ?: return null

        val targetName = if (blob.mimeType == "image/jpeg" && !doc.fileName.lowercase().endsWith(".jpg")) {
            "${doc.id}_preview.jpg"
        } else {
            doc.fileName
        }

        val saved = withContext(Dispatchers.IO) {
            DocumentProcessor.saveCloudBytesToLocalCache(context.applicationContext, doc.id, targetName, bytes)
        }
        _loadedFilesCache.value = _loadedFilesCache.value + (doc.id to saved)
        return saved
    }

    // --- Folders CRUD ---

    fun createFolder(
        name: String,
        colorHex: String,
        iconName: String,
        privateVault: Boolean,
        tags: List<String>
    ) {
        viewModelScope.launch {
            val res = repository.createFolder(name, colorHex, iconName, privateVault, tags)
            _snackbarMessage.value = if (res.isSuccess) {
                if (privateVault) "Encrypted Private Folder '$name' created."
                else "Folder '$name' created."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to create folder."
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val res = repository.deleteFolder(folderId)
            if (res.isSuccess) {
                if (_filter.value.folderId == folderId) {
                    _filter.value = _filter.value.copy(folderId = null)
                }
                _snackbarMessage.value = "Folder removed."
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete folder."
            }
        }
    }

    // --- Saved Lookup Websites (Highlighted Text -> Search with Website or Online) ---

    fun saveWebsite(name: String, urlTemplate: String, category: String) {
        viewModelScope.launch {
            val res = repository.saveWebsite(name, urlTemplate, category)
            _snackbarMessage.value = if (res.isSuccess) {
                "Saved '$name' for quick text lookups."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Invalid website URL."
            }
        }
    }

    fun recordWebsiteLookup(site: SavedWebsite?, highlightedText: String) {
        viewModelScope.launch {
            if (site != null) {
                repository.incrementWebsiteUsage(site.id)
            }
            repository.addSearchHistory(
                query = highlightedText.take(200),
                mode = "WEB_LOOKUP",
                aiAnswerPreview = site?.name ?: "Online Web Search",
                resultCount = 1L
            )
        }
    }

    fun deleteWebsite(siteId: String) {
        viewModelScope.launch {
            val res = repository.deleteWebsite(siteId)
            if (res.isSuccess) _snackbarMessage.value = "Website removed."
        }
    }

    private fun decryptDocumentView(doc: VaultDocument, key: SecretKey?): DecryptedDocumentView {
        if (!doc.encrypted) {
            return DecryptedDocumentView(
                raw = doc,
                displayTitle = doc.title,
                displaySummary = doc.summary,
                displayExtractedText = doc.extractedText,
                displayThumbnailBase64 = doc.thumbnailBase64,
                isLocked = false
            )
        }
        if (key == null || doc.encryptedPayload.isBlank() || doc.encryptionIv.isBlank()) {
            return DecryptedDocumentView(
                raw = doc,
                displayTitle = "Locked Private Document",
                displaySummary = "End-to-end encrypted. Unlock Private Vault to view and search.",
                displayExtractedText = "",
                displayThumbnailBase64 = "",
                isLocked = true
            )
        }
        val secret = VaultCryptoManager.decryptDocumentSecret(doc.encryptedPayload, doc.encryptionIv, key)
        return if (secret != null) {
            DecryptedDocumentView(
                raw = doc,
                displayTitle = secret.title,
                displaySummary = secret.summary,
                displayExtractedText = secret.extractedText,
                displayThumbnailBase64 = secret.thumbnailBase64,
                isLocked = false
            )
        } else {
            DecryptedDocumentView(
                raw = doc,
                displayTitle = "Locked Private Document",
                displaySummary = "Unable to decrypt with current vault key.",
                displayExtractedText = "",
                displayThumbnailBase64 = "",
                isLocked = true
            )
        }
    }

    private fun defaultCategoryForFile(cat: FileCategory): String = when (cat) {
        FileCategory.ARCHIVE -> "Archives"
        FileCategory.DOCX -> "Work & Study"
        else -> "Uncategorized"
    }

    private data class CoreDataBundle(
        val profile: UiState<UserProfile?>,
        val folders: UiState<List<VaultFolder>>,
        val docs: UiState<List<DecryptedDocumentView>>,
        val e2eeUnlocked: Boolean,
        val websites: UiState<List<SavedWebsite>>,
        val history: UiState<List<SearchHistoryEntry>>
    )

    private data class InteractionBundle(
        val filter: SearchFilter,
        val datePreset: DateFilterPreset,
        val aiMode: Boolean,
        val aiState: AiSearchUiState,
        val upload: UploadProgressState
    )
}
