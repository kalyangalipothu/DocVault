package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.BiometricHelper
import com.example.core.DarkModeOption
import com.example.core.DocumentProcessor
import com.example.data.VaultDocument
import com.example.ui.theme.VaultEncryptedGold
import com.example.viewmodel.VaultViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultAppShell(
    viewModel: VaultViewModel,
    isDarkActive: Boolean,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val loadedFiles by viewModel.loadedFilesCache.collectAsStateWithLifecycle()
    val loadingBlobId by viewModel.loadingBlobDocId.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedDocId by remember { mutableStateOf<String?>(null) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showE2eeDialog by remember { mutableStateOf(false) }
    var showWebsitesDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var quickEditDateDoc by remember { mutableStateOf<VaultDocument?>(null) }

    // Upload target context for pending pickers
    var pendingUploadFolderId by remember { mutableStateOf<String?>(null) }
    var pendingUploadDateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Camera capture launcher
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.uploadDocuments(
                context = context,
                uris = listOf(uri),
                targetFolderId = pendingUploadFolderId,
                uploadDateMillis = pendingUploadDateMillis
            )
        }
    }

    fun launchCameraCapture() {
        val uri = createTempCameraUri(context)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            viewModel.showMessage("Camera permission is needed to scan documents directly.")
        }
    }

    // Zero-permission Android Photo Picker (Play Policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 15)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadDocuments(
                context = context,
                uris = uris,
                targetFolderId = pendingUploadFolderId,
                uploadDateMillis = pendingUploadDateMillis
            )
        }
    }

    // Zero-permission SAF Document Picker for PDF, Word (.docx/.doc), RAR, ZIP, Images, TXT
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadDocuments(
                context = context,
                uris = uris,
                targetFolderId = pendingUploadFolderId,
                uploadDateMillis = pendingUploadDateMillis
            )
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Sub-screen: Document Detail Inspector
    val activeDetailDoc = remember(selectedDocId, state.allDocuments) {
        selectedDocId?.let { id -> state.allDocuments.firstOrNull { it.raw.id == id } }
    }

    if (activeDetailDoc != null) {
        DocumentDetailScreen(
            docView = activeDetailDoc,
            folders = state.folders,
            savedWebsites = state.savedWebsites,
            localFile = loadedFiles[activeDetailDoc.raw.id],
            isLoadingBlob = loadingBlobId == activeDetailDoc.raw.id,
            onBack = { selectedDocId = null },
            onRequestLoadFile = { viewModel.ensureDocumentFileLoaded(context, activeDetailDoc.raw) },
            onUnlockPrivateVault = { showE2eeDialog = true },
            onUpdateUploadDate = { newMillis ->
                viewModel.updateDocumentUploadDate(activeDetailDoc.raw.id, newMillis)
            },
            onUpdateDetails = { title, cat, tags, folderId ->
                viewModel.updateDocumentDetails(activeDetailDoc, title, cat, tags, folderId)
            },
            onRescanAi = { viewModel.rescanDocumentWithAi(context, activeDetailDoc) },
            onDelete = {
                viewModel.deleteDocument(context, activeDetailDoc.raw)
                selectedDocId = null
            },
            onLaunchWebsiteLookup = { site, phrase ->
                viewModel.recordWebsiteLookup(site, phrase)
            },
            onAddWebsite = { n, u, c -> viewModel.saveWebsite(n, u, c) },
            onNotify = { viewModel.showMessage(it) }
        )
    } else {
        val folderNameMap = remember(state.folders) {
            state.folders.associate { it.id to it.name }
        }
        val activeScreenTitle = remember(state.filter, state.folders) {
            when {
                state.filter.onlyPrivateVault -> "Private Vault (E2EE)"
                state.filter.folderId != null -> folderNameMap[state.filter.folderId] ?: "Folder"
                state.filter.aiCategory != null -> state.filter.aiCategory.orEmpty()
                else -> "DocVault"
            }
        }

        // Group filtered hits by formatted upload date for clean chronological scanning
        val groupedByDate = remember(state.filteredHits) {
            val fmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            state.filteredHits.groupBy { hit ->
                fmt.format(Date(hit.document.raw.uploadDateMillis))
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 320.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        // Account & Cloud Sync Banner
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (state.profile?.displayName?.firstOrNull() ?: 'D').uppercase(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.profile?.displayName?.ifBlank { "DocVault User" } ?: "DocVault",
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = state.profile?.email.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.CloudDone,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Cloud Synced · ${DocumentProcessor.formatBytes(state.profile?.storageUsedBytes ?: 0L)} off-device",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        NavigationDrawerItem(
                            label = { Text("All Documents (${state.allDocuments.size})") },
                            selected = state.filter.folderId == null && !state.filter.onlyPrivateVault,
                            icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = {
                                viewModel.selectFolderFilter(null, false)
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = {
                                Text(
                                    if (state.e2eeUnlocked) "Private Vault (Unlocked)"
                                    else "Private Vault (E2EE)"
                                )
                            },
                            selected = state.filter.onlyPrivateVault,
                            icon = {
                                Icon(
                                    imageVector = if (state.e2eeUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = VaultEncryptedGold
                                )
                            },
                            onClick = {
                                viewModel.selectFolderFilter(null, true)
                                scope.launch { drawerState.close() }
                                if (!state.e2eeUnlocked) {
                                    showE2eeDialog = true
                                }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("Saved Websites (${state.savedWebsites.size})") },
                            selected = false,
                            icon = { Icon(Icons.Filled.Language, contentDescription = null) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                showWebsitesDialog = true
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        // Custom Folders Section
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CUSTOM FOLDERS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showCreateFolderDialog = true
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("drawer_new_folder_button")
                            ) {
                                Icon(
                                    Icons.Filled.CreateNewFolder,
                                    contentDescription = "Create new folder",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (state.folders.isEmpty()) {
                            Text(
                                text = "No custom folders yet. Tap + to create standard or E2EE private folders.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        } else {
                            state.folders.forEach { folder ->
                                val parsedColor = runCatching {
                                    Color(android.graphics.Color.parseColor(folder.colorHex))
                                }.getOrDefault(MaterialTheme.colorScheme.primary)
                                NavigationDrawerItem(
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = folder.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (folder.privateVault) {
                                                Icon(
                                                    Icons.Filled.Lock,
                                                    contentDescription = "Encrypted",
                                                    tint = VaultEncryptedGold,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    },
                                    selected = state.filter.folderId == folder.id,
                                    icon = {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = parsedColor
                                        )
                                    },
                                    onClick = {
                                        viewModel.selectFolderFilter(folder.id, false)
                                        scope.launch { drawerState.close() }
                                        if (folder.privateVault && !state.e2eeUnlocked) {
                                            showE2eeDialog = true
                                        }
                                    }
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        // Sidebar Search & AI History Section
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "SEARCH & AI HISTORY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.searchHistory.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearAllHistory() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (state.searchHistory.isEmpty()) {
                            Text(
                                text = "Recent searches, AI questions, and website lookups will appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        } else {
                            state.searchHistory.take(12).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            scope.launch { drawerState.close() }
                                            if (entry.mode == "AI_ASK") {
                                                viewModel.askAiInSearchBar(entry.query)
                                            } else {
                                                viewModel.toggleAiSearchMode(false)
                                                viewModel.updateSearchQuery(entry.query)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (entry.mode) {
                                            "AI_ASK" -> Icons.Filled.AutoAwesome
                                            "WEB_LOOKUP" -> Icons.Filled.Language
                                            else -> Icons.Filled.Search
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (entry.mode == "AI_ASK") MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.query,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (entry.aiAnswerPreview.isNotBlank()) {
                                            Text(
                                                text = entry.aiAnswerPreview,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteHistoryItem(entry.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete history entry",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        NavigationDrawerItem(
                            label = { Text("Settings, Biometrics & Sync") },
                            selected = false,
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                showSettingsSheet = true
                            }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = activeScreenTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (state.filter.onlyPrivateVault) {
                                    Text(
                                        text = if (state.e2eeUnlocked) "AES-256-GCM Unlocked" else "Locked — tap to unlock",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultEncryptedGold
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("drawer_menu_button")
                            ) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open navigation sidebar")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    viewModel.setDarkMode(
                                        if (isDarkActive) DarkModeOption.LIGHT else DarkModeOption.DARK
                                    )
                                },
                                modifier = Modifier.testTag("topbar_dark_mode_button")
                            ) {
                                Icon(
                                    imageVector = if (isDarkActive) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    contentDescription = if (isDarkActive) "Switch to light mode" else "Switch to dark mode"
                                )
                            }
                            IconButton(
                                onClick = { viewModel.toggleSortOrder() },
                                modifier = Modifier.testTag("sort_date_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapVert,
                                    contentDescription = if (state.filter.sortDescending) "Sorted newest first" else "Sorted oldest first"
                                )
                            }
                            IconButton(
                                onClick = { viewModel.setGridView(!prefs.gridView) },
                                modifier = Modifier.testTag("toggle_layout_button")
                            ) {
                                Icon(
                                    imageVector = if (prefs.gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                    contentDescription = if (prefs.gridView) "Switch to list view" else "Switch to grid view"
                                )
                            }
                            IconButton(
                                onClick = { showSettingsSheet = true },
                                modifier = Modifier.testTag("settings_button")
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = "Vault settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { showUploadSheet = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Upload") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("upload_fab")
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                ) {
                    SmartSearchBarSection(
                        query = state.filter.query,
                        aiSearchMode = state.aiSearchMode,
                        aiSearchState = state.aiSearchState,
                        filter = state.filter,
                        datePreset = state.datePreset,
                        history = state.searchHistory,
                        totalDocCount = state.allDocuments.size,
                        matchedCount = state.filteredHits.size,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        onCommitKeywordSearch = { viewModel.commitKeywordSearchToHistory(it) },
                        onToggleAiMode = { viewModel.toggleAiSearchMode(it) },
                        onAskAi = { viewModel.askAiInSearchBar(it) },
                        onClearAiAnswer = { viewModel.clearAiAnswer() },
                        onSelectDatePreset = { viewModel.applyDatePreset(it) },
                        onCustomDateRange = { s, e -> viewModel.applyCustomDateRange(s, e) },
                        onSelectFileCategory = { viewModel.selectFileCategoryFilter(it) },
                        onSelectAiCategory = { viewModel.selectAiCategoryFilter(it) },
                        onClearAllFilters = { viewModel.clearAllFilters() },
                        onDeleteHistoryItem = { viewModel.deleteHistoryItem(it) },
                        onOpenDocument = { docId -> selectedDocId = docId },
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )

                    UploadProgressBanner(progress = state.uploadProgress)

                    if (state.errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Text(
                                text = state.errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Private Vault Unlock Banner if viewing locked private docs
                    val hasLockedDocs = remember(state.filteredHits) {
                        state.filteredHits.any { it.document.isLocked }
                    }
                    if (hasLockedDocs && !state.e2eeUnlocked) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = VaultEncryptedGold.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showE2eeDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = VaultEncryptedGold,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "Encrypted Private Vault items hidden. Tap to unlock.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Unlock",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = VaultEncryptedGold
                                )
                            }
                        }
                    }

                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        state.filteredHits.isEmpty() -> {
                            val isFiltered = state.filter.query.isNotBlank() ||
                                state.filter.folderId != null ||
                                state.filter.onlyPrivateVault ||
                                state.filter.aiCategory != null ||
                                state.filter.fileCategory != null ||
                                state.filter.startDateMillis != null
                            EmptyVaultState(
                                isFiltered = isFiltered,
                                onUploadClick = { showUploadSheet = true },
                                onResetFilters = { viewModel.clearAllFilters() }
                            )
                        }
                        prefs.gridView -> {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 158.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 88.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.filteredHits, key = { it.document.raw.id }) { hit ->
                                    DocumentGridTile(
                                        hit = hit,
                                        onClick = { selectedDocId = hit.document.raw.id }
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 88.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                groupedByDate.forEach { (dateHeader, hitsForDate) ->
                                    item(key = "header_$dateHeader") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp, bottom = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = dateHeader,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "${hitsForDate.size} item(s)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    items(hitsForDate, key = { it.document.raw.id }) { hit ->
                                        DocumentVaultCard(
                                            hit = hit,
                                            folderName = hit.document.raw.folderId?.let { folderNameMap[it] },
                                            onClick = { selectedDocId = hit.document.raw.id },
                                            onEditDateClick = { quickEditDateDoc = hit.document.raw }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs & Bottom Sheets ---

    if (showUploadSheet) {
        UploadSourceSheet(
            folders = state.folders,
            initialFolderId = state.filter.folderId,
            e2eeUnlocked = state.e2eeUnlocked,
            onDismiss = { showUploadSheet = false },
            onPickCamera = { folderId, dateMillis ->
                pendingUploadFolderId = folderId
                pendingUploadDateMillis = dateMillis
                val hasCam = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCam) {
                    launchCameraCapture()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onPickGallery = { folderId, dateMillis ->
                pendingUploadFolderId = folderId
                pendingUploadDateMillis = dateMillis
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFiles = { folderId, dateMillis ->
                pendingUploadFolderId = folderId
                pendingUploadDateMillis = dateMillis
                filePickerLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "image/*",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                        "application/vnd.rar",
                        "application/x-rar-compressed",
                        "application/zip",
                        "text/plain",
                        "*/*"
                    )
                )
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            e2eeConfigured = state.profile?.e2eeEnabled == true,
            onDismiss = { showCreateFolderDialog = false },
            onRequireE2eeSetup = {
                showCreateFolderDialog = false
                showE2eeDialog = true
            },
            onCreate = { name, color, icon, priv, tags ->
                viewModel.createFolder(name, color, icon, priv, tags)
            }
        )
    }

    if (showE2eeDialog) {
        val isSetup = state.profile?.e2eeEnabled != true
        E2eePassphraseDialog(
            isSetupMode = isSetup,
            hasBiometricWrappedKey = viewModel.hasBiometricWrappedVaultKey(),
            onDismiss = { showE2eeDialog = false },
            onBiometricUnlockClick = {
                val activity = context as? FragmentActivity
                if (activity != null) {
                    BiometricHelper.authenticate(
                        activity = activity,
                        title = "Unlock Private Vault",
                        subtitle = "Verify biometrics to unwrap your AES-256-GCM encryption key",
                        onSuccess = {
                            if (viewModel.unlockE2eeFromBiometricWrap()) {
                                showE2eeDialog = false
                            } else {
                                viewModel.showMessage("Biometric key unavailable — enter your passphrase.")
                            }
                        },
                        onError = { viewModel.showMessage(it) }
                    )
                }
            },
            onSubmitPassphrase = { pass, saveBio, cb ->
                if (isSetup) {
                    viewModel.setupE2eeVault(pass, saveBio, cb)
                } else {
                    viewModel.unlockE2eeWithPassphrase(pass, saveBio, cb)
                }
            }
        )
    }

    if (showWebsitesDialog) {
        SavedWebsitesDialog(
            websites = state.savedWebsites,
            onDismiss = { showWebsitesDialog = false },
            onAddWebsite = { n, u, c -> viewModel.saveWebsite(n, u, c) },
            onDeleteWebsite = { viewModel.deleteWebsite(it) }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            profile = state.profile,
            docCount = state.allDocuments.size,
            darkMode = prefs.darkMode,
            biometricAppLock = prefs.biometricAppLock,
            e2eeUnlocked = state.e2eeUnlocked,
            e2eeFingerprint = state.e2eeFingerprint,
            isBatchOrganizing = state.isBatchOrganizing,
            onDismiss = { showSettingsSheet = false },
            onSetDarkMode = { viewModel.setDarkMode(it) },
            onToggleBiometricLock = { enabled ->
                if (enabled) {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        BiometricHelper.authenticate(
                            activity = activity,
                            title = "Enable Biometric App Lock",
                            subtitle = "Confirm your fingerprint, face, or device PIN",
                            onSuccess = {
                                viewModel.setBiometricAppLock(true)
                                viewModel.showMessage("Biometric App Lock enabled.")
                            },
                            onError = { viewModel.showMessage(it) }
                        )
                    }
                } else {
                    viewModel.setBiometricAppLock(false)
                    viewModel.showMessage("Biometric App Lock disabled.")
                }
            },
            onSetupOrUnlockE2ee = {
                showSettingsSheet = false
                showE2eeDialog = true
            },
            onLockE2ee = { viewModel.lockE2eeVault() },
            onAutoCategorizeAll = { viewModel.autoCategorizeAllUncategorized() },
            onManageWebsites = { showWebsitesDialog = true },
            onSignOut = onSignOut
        )
    }

    quickEditDateDoc?.let { targetDoc ->
        val dateState = rememberDatePickerState(initialSelectedDateMillis = targetDoc.uploadDateMillis)
        DatePickerDialog(
            onDismissRequest = { quickEditDateDoc = null },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        viewModel.updateDocumentUploadDate(targetDoc.id, it)
                    }
                    quickEditDateDoc = null
                }) {
                    Text("Save Date")
                }
            },
            dismissButton = {
                TextButton(onClick = { quickEditDateDoc = null }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }
}

private fun createTempCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val photoFile = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
}
