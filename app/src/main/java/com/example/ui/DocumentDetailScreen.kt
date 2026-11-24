package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.DocumentProcessor
import com.example.data.DecryptedDocumentView
import com.example.data.SavedWebsite
import com.example.data.VaultFolder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.VaultEncryptedGold
import com.example.ui.theme.VaultSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailScreen(
    docView: DecryptedDocumentView,
    folders: List<VaultFolder>,
    savedWebsites: List<SavedWebsite>,
    localFile: File?,
    isLoadingBlob: Boolean,
    onBack: () -> Unit,
    onRequestLoadFile: () -> Unit,
    onUnlockPrivateVault: () -> Unit,
    onUpdateUploadDate: (Long) -> Unit,
    onUpdateDetails: (title: String, category: String, tags: List<String>, folderId: String?) -> Unit,
    onRescanAi: () -> Unit,
    onDelete: () -> Unit,
    onLaunchWebsiteLookup: (SavedWebsite?, String) -> Unit,
    onAddWebsite: (name: String, urlTemplate: String, category: String) -> Unit,
    onNotify: (String) -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showDatePicker by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddWebsiteDialog by remember { mutableStateOf(false) }

    // Trigger lazy off-device cloud blob load on screen entry
    LaunchedEffect(docView.raw.id, docView.isLocked) {
        if (!docView.isLocked) {
            onRequestLoadFile()
        }
    }

    // Read-only BasicTextField with TextFieldValue gives native long-press text highlighting
    // AND exposes the exact highlighted substring for "Search with this website or online"!
    var ocrTextField by remember(docView.displayExtractedText) {
        mutableStateOf(TextFieldValue(docView.displayExtractedText))
    }

    val highlightedText = remember(ocrTextField) {
        val sel = ocrTextField.selection
        if (!sel.collapsed) {
            val start = minOf(sel.start, sel.end).coerceIn(0, ocrTextField.text.length)
            val end = maxOf(sel.start, sel.end).coerceIn(0, ocrTextField.text.length)
            ocrTextField.text.substring(start, end).trim()
        } else ""
    }

    // Smart-detected tracking codes, URLs, emails, and IDs for 1-tap highlighting
    val smartTokens = remember(docView.displayExtractedText) {
        extractLookupTokens(docView.displayExtractedText)
    }

    val folderName = remember(folders, docView.raw.folderId) {
        folders.firstOrNull { it.id == docView.raw.folderId }?.name
    }
    val formattedDate = remember(docView.raw.uploadDateMillis) {
        SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())
            .format(Date(docView.raw.uploadDateMillis))
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = docView.displayTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to vault"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.testTag("edit_date_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EditCalendar,
                            contentDescription = "Change upload date"
                        )
                    }
                    if (!docView.isLocked) {
                        IconButton(
                            onClick = onRescanAi,
                            modifier = Modifier.testTag("rescan_ocr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DocumentScanner,
                                contentDescription = "Re-scan OCR with AI"
                            )
                        }
                        IconButton(
                            onClick = { showEditDialog = true },
                            modifier = Modifier.testTag("edit_doc_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit document details"
                            )
                        }
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("delete_doc_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete document",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (docView.isLocked) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, VaultEncryptedGold, RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Encrypted document locked",
                            tint = VaultEncryptedGold,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "End-to-End Encrypted Document",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "This file is encrypted with AES-256-GCM inside your Private Vault. Unlock with your Vault Passphrase or biometrics to decrypt locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onUnlockPrivateVault,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("detail_unlock_vault_button")
                        ) {
                            Text("Unlock Private Vault")
                        }
                    }
                }
            } else {
                // 1. Multi-Format Document Preview Pane
                DocumentPreviewPane(
                    docView = docView,
                    localFile = localFile,
                    isLoadingBlob = isLoadingBlob
                )

                // 2. Metadata & Editable Upload Date Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Upload Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formattedDate,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            FilledTonalButton(
                                onClick = { showDatePicker = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.EditCalendar,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Change Date", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MiniPill(
                                text = docView.raw.aiCategory,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            MiniPill(
                                text = "${docView.raw.fileCategory} · ${DocumentProcessor.formatBytes(docView.raw.fileSizeBytes)}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (folderName != null) {
                                MiniPill(
                                    text = "Folder: $folderName",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            if (docView.raw.encrypted) {
                                MiniPill(
                                    text = "AES-256-GCM E2EE",
                                    containerColor = VaultEncryptedGold.copy(alpha = 0.2f),
                                    contentColor = VaultEncryptedGold
                                )
                            }
                            if (docView.raw.hasCloudBlob) {
                                MiniPill(
                                    text = "Cloud Synced Off-Device",
                                    containerColor = VaultSuccess.copy(alpha = 0.16f),
                                    contentColor = VaultSuccess
                                )
                            }
                            docView.raw.tags.forEach { tag ->
                                MiniPill(
                                    text = "#$tag",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 3. AI Summary Card
                if (docView.displaySummary.isNotBlank()) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "AI Document Summary",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = docView.displaySummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 4. Contextual Highlighted Text -> "Search with this website or online" Bar
                AnimatedVisibility(visible = highlightedText.isNotEmpty()) {
                    HighlightedWebLookupBar(
                        highlightedText = highlightedText,
                        savedWebsites = savedWebsites,
                        onSearchWebsite = { site ->
                            launchSafeLookupUrl(context, buildWebsiteLookupUrl(site.urlTemplate, highlightedText))
                            onLaunchWebsiteLookup(site, highlightedText)
                        },
                        onSearchOnline = {
                            val encoded = URLEncoder.encode(highlightedText, "UTF-8")
                            launchSafeLookupUrl(context, "https://www.google.com/search?q=$encoded")
                            onLaunchWebsiteLookup(null, highlightedText)
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(highlightedText))
                            onNotify("Copied \"$highlightedText\"")
                        },
                        onAddWebsiteClick = { showAddWebsiteDialog = true }
                    )
                }

                // 5. Smart Detected Lookup Chips (1-tap highlight for tracking numbers, URLs, codes)
                if (smartTokens.isNotEmpty()) {
                    Column {
                        Text(
                            text = "Tap any detected detail to search on your websites or online",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            smartTokens.forEach { token ->
                                AssistChip(
                                    onClick = {
                                        val idx = ocrTextField.text.indexOf(token)
                                        if (idx >= 0) {
                                            ocrTextField = ocrTextField.copy(
                                                selection = TextRange(idx, idx + token.length)
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = token,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Language,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // 6. Highlightable Extracted OCR / Parsed Document Text Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Recognized Text (Long-press to Highlight)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Highlight any word or tracking number to search your websites or online",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (docView.displayExtractedText.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(docView.displayExtractedText))
                                        onNotify("Copied all recognized text.")
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy all recognized text",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        if (docView.displayExtractedText.isBlank()) {
                            Text(
                                text = "No text detected yet. Tap the Scan icon above to run AI OCR.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            BasicTextField(
                                value = ocrTextField,
                                onValueChange = { updated ->
                                    // Preserve original OCR text while tracking live highlight selection range
                                    ocrTextField = updated.copy(text = docView.displayExtractedText)
                                },
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(12.dp)
                                    .testTag("ocr_selectable_text")
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = docView.raw.uploadDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { onUpdateUploadDate(it) }
                    showDatePicker = false
                }) {
                    Text("Save Date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showEditDialog) {
        EditDocumentDialog(
            docView = docView,
            folders = folders,
            onDismiss = { showEditDialog = false },
            onSave = { title, category, tags, folderId ->
                onUpdateDetails(title, category, tags, folderId)
                showEditDialog = false
            }
        )
    }

    if (showAddWebsiteDialog) {
        AddWebsiteDialog(
            onDismiss = { showAddWebsiteDialog = false },
            onSave = { name, urlTemplate, category ->
                onAddWebsite(name, urlTemplate, category)
                showAddWebsiteDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Document?") },
            text = {
                Text("This permanently deletes '${docView.displayTitle}' from your device and off-device cloud storage.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HighlightedWebLookupBar(
    highlightedText: String,
    savedWebsites: List<SavedWebsite>,
    onSearchWebsite: (SavedWebsite) -> Unit,
    onSearchOnline: () -> Unit,
    onCopy: () -> Unit,
    onAddWebsiteClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(18.dp))
            .testTag("highlighted_lookup_bar")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Search with Website or Online",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "\"$highlightedText\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy highlighted text",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSearchOnline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("search_online_button")
                ) {
                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Search Online")
                }

                savedWebsites.forEach { site ->
                    FilledTonalButton(
                        onClick = { onSearchWebsite(site) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(site.name, maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onAddWebsiteClick,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Website")
                }
            }
        }
    }
}

@Composable
private fun DocumentPreviewPane(
    docView: DecryptedDocumentView,
    localFile: File?,
    isLoadingBlob: Boolean
) {
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var currentPdfPage by remember(docView.raw.id) { mutableIntStateOf(0) }
    var pdfTotalPages by remember(docView.raw.id) { mutableIntStateOf(docView.raw.pageCount.coerceAtLeast(1)) }
    var renderedBitmap by remember(docView.raw.id, localFile?.absolutePath, currentPdfPage) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(docView.raw.id, localFile?.absolutePath, currentPdfPage) {
        renderedBitmap = withContext(Dispatchers.IO) {
            when {
                localFile != null && localFile.exists() &&
                    docView.raw.fileCategory == "PDF" &&
                    localFile.extension.equals("pdf", ignoreCase = true) -> {
                    val res = DocumentProcessor.renderPdfPage(localFile, currentPdfPage, 1280)
                    if (res != null) {
                        pdfTotalPages = res.second
                        res.first
                    } else null
                }
                localFile != null && localFile.exists() &&
                    (docView.raw.fileCategory == "IMAGE" || localFile.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")) -> {
                    DocumentProcessor.decodeSampledBitmap(localFile, 1440)
                }
                docView.displayThumbnailBase64.isNotBlank() -> {
                    runCatching {
                        val b = Base64.decode(docView.displayThumbnailBase64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(b, 0, b.size)
                    }.getOrNull()
                }
                else -> null
            }
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
    ) {
        Column {
            val bmp = renderedBitmap
            if (bmp != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 3f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = docView.displayTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                    )
                    if (isLoadingBlob) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Loading HD from cloud…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (docView.raw.fileCategory == "PDF" && pdfTotalPages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (currentPdfPage > 0) currentPdfPage-- },
                            enabled = currentPdfPage > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                        }
                        Text(
                            text = "PDF Page ${currentPdfPage + 1} of $pdfTotalPages",
                            style = MaterialTheme.typography.labelMedium
                        )
                        IconButton(
                            onClick = { if (currentPdfPage < pdfTotalPages - 1) currentPdfPage++ },
                            enabled = currentPdfPage < pdfTotalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingBlob) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            Icon(
                                imageVector = formatIcon(docView.raw.fileCategory),
                                contentDescription = docView.raw.fileCategory,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = docView.raw.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (docView.raw.fileCategory) {
                                "ARCHIVE" -> "Archive inspected (${docView.raw.pageCount} entries listed below)"
                                "DOCX" -> "Word document extracted (${docView.raw.pageCount} page(s))"
                                "PDF" -> "PDF document (${docView.raw.pageCount} page(s))"
                                else -> "${docView.raw.mimeType} · ${DocumentProcessor.formatBytes(docView.raw.fileSizeBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (docView.raw.encrypted) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = "Decrypted in memory",
                            tint = VaultEncryptedGold
                        )
                    } else if (docView.raw.hasCloudBlob) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Cloud synced",
                            tint = VaultSuccess
                        )
                    }
                }
            }
        }
    }
}

private fun buildWebsiteLookupUrl(template: String, query: String): String {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val trimmed = template.trim()
    return when {
        trimmed.contains("{query}", ignoreCase = true) -> {
            trimmed.replace("{query}", encoded, ignoreCase = true)
        }
        trimmed.contains("?") -> "$trimmed&q=$encoded"
        trimmed.endsWith("/") -> "$trimmed?q=$encoded"
        else -> "$trimmed/?q=$encoded"
    }
}

private fun launchSafeLookupUrl(context: Context, url: String) {
    val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return
    val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun extractLookupTokens(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val found = LinkedHashSet<String>()
    val urlRegex = Regex("""https?://[^\s<>"')]+""")
    val emailRegex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
    val trackingRegex = Regex("""\b[A-Z0-9]{8,22}\b""")

    urlRegex.findAll(text).take(4).forEach { found.add(it.value) }
    emailRegex.findAll(text).take(3).forEach { found.add(it.value) }
    trackingRegex.findAll(text)
        .map { it.value }
        .filter { token -> token.any { it.isDigit() } && token.length >= 8 }
        .take(6)
        .forEach { found.add(it) }

    return found.toList().take(10)
}
