package com.example.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.DocumentProcessor
import com.example.core.SearchFilter
import com.example.core.SearchHit
import com.example.data.AiCategories
import com.example.data.DateFilterPreset
import com.example.data.DecryptedDocumentView
import com.example.data.SearchHistoryEntry
import com.example.ui.theme.VaultEncryptedGold
import com.example.ui.theme.VaultSuccess
import com.example.viewmodel.AiSearchUiState
import com.example.viewmodel.UploadProgressState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartSearchBarSection(
    query: String,
    aiSearchMode: Boolean,
    aiSearchState: AiSearchUiState,
    filter: SearchFilter,
    datePreset: DateFilterPreset,
    history: List<SearchHistoryEntry>,
    totalDocCount: Int,
    matchedCount: Int,
    onQueryChange: (String) -> Unit,
    onCommitKeywordSearch: (String) -> Unit,
    onToggleAiMode: (Boolean) -> Unit,
    onAskAi: (String) -> Unit,
    onClearAiAnswer: () -> Unit,
    onSelectDatePreset: (DateFilterPreset) -> Unit,
    onCustomDateRange: (Long?, Long?) -> Unit,
    onSelectFileCategory: (String?) -> Unit,
    onSelectAiCategory: (String?) -> Unit,
    onClearAllFilters: () -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilters by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }

    val activeFilterCount = remember(filter, datePreset) {
        var c = 0
        if (datePreset != DateFilterPreset.ALL) c++
        if (filter.fileCategory != null) c++
        if (filter.aiCategory != null) c++
        if (filter.tag != null) c++
        c
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Unified Search + Inbuilt AI Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = if (aiSearchMode) "Ask AI about any document or photo…"
                        else "Search OCR text, tracking #, tags, files…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    IconButton(
                        onClick = { onToggleAiMode(!aiSearchMode) },
                        modifier = Modifier.testTag("ai_search_toggle")
                    ) {
                        Icon(
                            imageVector = if (aiSearchMode) Icons.Filled.AutoAwesome else Icons.Filled.Search,
                            contentDescription = if (aiSearchMode) "AI Search mode active" else "Switch to AI Search mode",
                            tint = if (aiSearchMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                onQueryChange("")
                                onClearAiAnswer()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (aiSearchMode && query.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    keyboard?.hide()
                                    searchFocused = false
                                    onAskAi(query)
                                },
                                modifier = Modifier.testTag("search_submit_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Ask AI",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { showFilters = !showFilters },
                                modifier = Modifier.testTag("toggle_filters_button")
                            ) {
                                Box {
                                    Icon(
                                        imageVector = Icons.Filled.FilterList,
                                        contentDescription = "Toggle date and format filters",
                                        tint = if (activeFilterCount > 0 || showFilters) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (activeFilterCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondary)
                                                .align(Alignment.TopEnd)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedBorderColor = if (aiSearchMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        searchFocused = false
                        if (aiSearchMode) onAskAi(query) else onCommitKeywordSearch(query)
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .testTag("vault_search_input")
            )
        }

        // Quick Mode & Instant Count Pill Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (aiSearchMode) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onToggleAiMode(!aiSearchMode) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (aiSearchMode) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (aiSearchMode) "AI Ask Active" else "Ask AI",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (aiSearchMode) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (query.isNotBlank() && !aiSearchMode) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                keyboard?.hide()
                                searchFocused = false
                                onAskAi(query)
                            }
                            .testTag("ask_ai_quick_button")
                    ) {
                        Text(
                            text = "Ask AI this →",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Text(
                text = "$matchedCount / $totalDocCount docs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Inline Search Bar History Panel (shown when focused & history exists)
        AnimatedVisibility(visible = searchFocused && history.isNotEmpty() && aiSearchState is AiSearchUiState.Idle) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Recent Searches & AI Prompts",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = { searchFocused = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Hide", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    history.take(5).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    searchFocused = false
                                    keyboard?.hide()
                                    if (entry.mode == "AI_ASK") {
                                        onAskAi(entry.query)
                                    } else {
                                        onToggleAiMode(false)
                                        onQueryChange(entry.query)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (entry.mode) {
                                    "AI_ASK" -> Icons.Filled.AutoAwesome
                                    "WEB_LOOKUP" -> Icons.Filled.Language
                                    else -> Icons.Filled.Search
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (entry.mode == "AI_ASK") MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.query,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.aiAnswerPreview.isNotBlank()) {
                                    Text(
                                        text = entry.aiAnswerPreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteHistoryItem(entry.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove history item",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Expandable Date, Format & AI Category Filters
        AnimatedVisibility(visible = showFilters) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Date & File Filters",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (activeFilterCount > 0) {
                            TextButton(onClick = onClearAllFilters) {
                                Text("Reset All")
                            }
                        }
                    }

                    // Date Presets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DateFilterPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = datePreset == preset,
                                onClick = {
                                    if (preset == DateFilterPreset.CUSTOM) {
                                        showDateRangePicker = true
                                    } else {
                                        onSelectDatePreset(preset)
                                    }
                                },
                                label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = if (preset == DateFilterPreset.CUSTOM) {
                                    {
                                        Icon(
                                            Icons.Filled.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // File Type Filter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val fileTypes = listOf(
                            null to "All Types",
                            "IMAGE" to "Photos",
                            "PDF" to "PDF",
                            "DOCX" to "Word",
                            "ARCHIVE" to "RAR / ZIP",
                            "TEXT" to "Text"
                        )
                        fileTypes.forEach { (key, label) ->
                            FilterChip(
                                selected = filter.fileCategory == key,
                                onClick = { onSelectFileCategory(key) },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // AI Category Filter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiCategories.ALL.forEach { cat ->
                            FilterChip(
                                selected = filter.aiCategory == cat,
                                onClick = { onSelectAiCategory(cat) },
                                label = { Text(cat, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        }

        // Inbuilt AI Assistant Answer Panel
        AnimatedVisibility(visible = aiSearchState !is AiSearchUiState.Idle) {
            AiSearchAnswerCard(
                state = aiSearchState,
                onDismiss = onClearAiAnswer,
                onOpenDocument = onOpenDocument,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }

    if (showDateRangePicker) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.startDateMillis,
            initialSelectedEndDateMillis = filter.endDateMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onCustomDateRange(
                        rangeState.selectedStartDateMillis,
                        rangeState.selectedEndDateMillis
                    )
                    showDateRangePicker = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DateRangePicker(
                state = rangeState,
                modifier = Modifier.height(420.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSearchAnswerCard(
    state: AiSearchUiState,
    onDismiss: () -> Unit,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderBrush = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary
        )
    )
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, borderBrush, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Vault AI Assistant",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close AI answer",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            when (state) {
                is AiSearchUiState.Idle -> Unit
                is AiSearchUiState.Streaming -> {
                    if (state.partialAnswer.isBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Scanning ${state.sources.size} matching document(s)…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = state.partialAnswer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                is AiSearchUiState.Success -> {
                    Text(
                        text = state.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (state.sources.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Referenced Documents",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            state.sources.take(4).forEach { doc ->
                                AssistChip(
                                    onClick = { onOpenDocument(doc.raw.id) },
                                    label = {
                                        Text(
                                            text = doc.displayTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = formatIcon(doc.raw.fileCategory),
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                is AiSearchUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UploadProgressBanner(progress: UploadProgressState) {
    AnimatedVisibility(visible = progress.active) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = progress.statusMessage,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${progress.processedCount}/${progress.totalCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentVaultCard(
    hit: SearchHit,
    folderName: String?,
    onClick: () -> Unit,
    onEditDateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc = hit.document
    val dateLabel = remember(doc.raw.uploadDateMillis) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(doc.raw.uploadDateMillis))
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("doc_card_${doc.raw.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            DocumentThumbnailBox(
                doc = doc,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = doc.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (doc.raw.encrypted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = if (doc.isLocked) Icons.Filled.Lock else Icons.Filled.VerifiedUser,
                            contentDescription = "End-to-end encrypted",
                            tint = VaultEncryptedGold,
                            modifier = Modifier.size(17.dp)
                        )
                    } else if (doc.raw.hasCloudBlob) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Synced off-device",
                            tint = VaultSuccess,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onEditDateClick)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EditCalendar,
                                contentDescription = "Change upload date",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Text(
                        text = "${doc.raw.fileCategory} · ${DocumentProcessor.formatBytes(doc.raw.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                if (!hit.matchSnippet.isNullOrBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = hit.matchSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (doc.displaySummary.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = doc.displaySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(7.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MiniPill(
                        text = doc.raw.aiCategory,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (!folderName.isNullOrBlank()) {
                        MiniPill(
                            text = folderName,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    doc.raw.tags.take(2).forEach { tag ->
                        MiniPill(
                            text = "#$tag",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentGridTile(
    hit: SearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc = hit.document
    val dateLabel = remember(doc.raw.uploadDateMillis) {
        SimpleDateFormat("MMM d, yy", Locale.getDefault()).format(Date(doc.raw.uploadDateMillis))
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
            ) {
                DocumentThumbnailBox(
                    doc = doc,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xBF0E1217),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = doc.raw.fileCategory,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = doc.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$dateLabel · ${doc.raw.aiCategory}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun DocumentThumbnailBox(
    doc: DecryptedDocumentView,
    modifier: Modifier = Modifier
) {
    val thumbBitmap = remember(doc.displayThumbnailBase64) {
        if (doc.displayThumbnailBase64.isNotBlank()) {
            runCatching {
                val bytes = Base64.decode(doc.displayThumbnailBase64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (doc.isLocked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Encrypted document",
                    tint = VaultEncryptedGold,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "E2EE",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultEncryptedGold
                )
            }
        } else if (thumbBitmap != null) {
            Image(
                bitmap = thumbBitmap,
                contentDescription = doc.displayTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = formatIcon(doc.raw.fileCategory),
                    contentDescription = doc.raw.fileCategory,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = doc.raw.fileCategory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun MiniPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyVaultState(
    isFiltered: Boolean,
    onUploadClick: () -> Unit,
    onResetFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_vault_1795511574360),
            contentDescription = "Empty document vault illustration",
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(22.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isFiltered) "No matching documents" else "Your DocVault is Ready",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isFiltered) {
                "Try adjusting your keyword, date filter, or ask the in-built AI assistant."
            } else {
                "Upload photos from Camera or Gallery, or import PDFs, Word docs, and RAR/ZIP archives. Gemini OCR & smart categorization run automatically."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(18.dp))
        if (isFiltered) {
            TextButton(onClick = onResetFilters) {
                Text("Clear all filters")
            }
        } else {
            androidx.compose.material3.Button(
                onClick = onUploadClick,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("empty_upload_button")
            ) {
                Text("Upload First Document")
            }
        }
    }
}

fun formatIcon(fileCategory: String): ImageVector = when (fileCategory) {
    "IMAGE" -> Icons.Filled.Image
    "PDF" -> Icons.Filled.PictureAsPdf
    "DOCX" -> Icons.Filled.Description
    "ARCHIVE" -> Icons.Filled.Archive
    else -> Icons.Filled.InsertDriveFile
}
