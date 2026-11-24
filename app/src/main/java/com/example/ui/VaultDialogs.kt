package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.DarkModeOption
import com.example.core.DocumentProcessor
import com.example.data.AiCategories
import com.example.data.DecryptedDocumentView
import com.example.data.SavedWebsite
import com.example.data.UserProfile
import com.example.data.VaultFolder
import com.example.ui.theme.VaultEncryptedGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSourceSheet(
    folders: List<VaultFolder>,
    initialFolderId: String?,
    e2eeUnlocked: Boolean,
    onDismiss: () -> Unit,
    onPickCamera: (folderId: String?, uploadDateMillis: Long) -> Unit,
    onPickGallery: (folderId: String?, uploadDateMillis: Long) -> Unit,
    onPickFiles: (folderId: String?, uploadDateMillis: Long) -> Unit
) {
    var selectedFolderId by remember { mutableStateOf(initialFolderId) }
    var uploadDateMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val dateLabel = remember(uploadDateMillis) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uploadDateMillis))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Add to DocVault",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Auto-scanned with Gemini OCR, categorized, and synced off-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            // Upload Date Selection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { showDatePicker = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Upload Date", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "$dateLabel  (Tap to change)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Destination Folder", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedFolderId == null,
                        onClick = { selectedFolderId = null },
                        label = { Text("Unfiled") }
                    )
                    folders.forEach { folder ->
                        val disabled = folder.privateVault && !e2eeUnlocked
                        FilterChip(
                            selected = selectedFolderId == folder.id,
                            enabled = !disabled,
                            onClick = { selectedFolderId = folder.id },
                            label = {
                                Text(
                                    if (disabled) "${folder.name} (Locked)" else folder.name
                                )
                            },
                            leadingIcon = if (folder.privateVault) {
                                { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            UploadOptionTile(
                icon = Icons.Filled.CameraAlt,
                title = "Capture with Camera",
                subtitle = "Snap receipts, IDs, bills, or paper forms directly",
                testTag = "upload_camera_button",
                onClick = {
                    onDismiss()
                    onPickCamera(selectedFolderId, uploadDateMillis)
                }
            )
            Spacer(Modifier.height(10.dp))
            UploadOptionTile(
                icon = Icons.Filled.PhotoLibrary,
                title = "Upload Photos from Device",
                subtitle = "Pick one or multiple JPG / PNG images using Photo Picker",
                testTag = "upload_gallery_button",
                onClick = {
                    onDismiss()
                    onPickGallery(selectedFolderId, uploadDateMillis)
                }
            )
            Spacer(Modifier.height(10.dp))
            UploadOptionTile(
                icon = Icons.Filled.UploadFile,
                title = "Import Documents & Archives",
                subtitle = "PDF, Word (.docx/.doc), RAR, ZIP, TXT, and images",
                testTag = "upload_files_button",
                onClick = {
                    onDismiss()
                    onPickFiles(selectedFolderId, uploadDateMillis)
                }
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = uploadDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { uploadDateMillis = it }
                    showDatePicker = false
                }) { Text("Set Date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun UploadOptionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CreateFolderDialog(
    e2eeConfigured: Boolean,
    onDismiss: () -> Unit,
    onRequireE2eeSetup: () -> Unit,
    onCreate: (name: String, colorHex: String, iconName: String, privateVault: Boolean, tags: List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var privateVault by remember { mutableStateOf(false) }
    val colors = listOf("#0D5C63", "#C86D2E", "#3A506B", "#1B8755", "#7B3F61", "#9B2C2C")
    var selectedColor by remember { mutableStateOf(colors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g., Tax Returns, Passports, Warranties") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = { Text("Default Tags (comma separated)") },
                    placeholder = { Text("tax, finance, 2026") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { hex ->
                        val parsedColor = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(parsedColor)
                                .border(
                                    width = if (selectedColor == hex) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Private Folder (E2EE)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "AES-256-GCM end-to-end encryption for sensitive docs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = privateVault,
                        onCheckedChange = { checked ->
                            if (checked && !e2eeConfigured) {
                                onRequireE2eeSetup()
                            } else {
                                privateVault = checked
                            }
                        },
                        modifier = Modifier.testTag("folder_private_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onCreate(
                        name.trim(),
                        selectedColor,
                        if (privateVault) "lock" else "folder",
                        privateVault,
                        tags
                    )
                    onDismiss()
                },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun E2eePassphraseDialog(
    isSetupMode: Boolean,
    hasBiometricWrappedKey: Boolean,
    onDismiss: () -> Unit,
    onBiometricUnlockClick: () -> Unit,
    onSubmitPassphrase: (passphrase: String, saveForBiometrics: Boolean, onResult: (Boolean, String) -> Unit) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var saveForBiometrics by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.EnhancedEncryption,
                contentDescription = null,
                tint = VaultEncryptedGold
            )
        },
        title = {
            Text(if (isSetupMode) "Set Up Private Vault (E2EE)" else "Unlock Private Vault")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isSetupMode) {
                        "Create a Vault Passphrase to derive your AES-256-GCM encryption key. Use the same passphrase on any device to decrypt your Private Folders."
                    } else {
                        "Enter your Vault Passphrase or use biometrics to decrypt Private Folder contents locally."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!isSetupMode && hasBiometricWrappedKey) {
                    OutlinedButton(
                        onClick = onBiometricUnlockClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unlock with Biometrics")
                    }
                    HorizontalDivider()
                }

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        errorMsg = null
                    },
                    label = { Text("Vault Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isSetupMode) {
                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = {
                            confirmPassphrase = it
                            errorMsg = null
                        },
                        label = { Text("Confirm Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveForBiometrics,
                        onCheckedChange = { saveForBiometrics = it }
                    )
                    Text(
                        text = "Store key wrapped in Android Keystore for biometric unlock",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (errorMsg != null) {
                    Text(
                        text = errorMsg.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && passphrase.length >= 6,
                onClick = {
                    if (isSetupMode && passphrase != confirmPassphrase) {
                        errorMsg = "Passphrases do not match."
                        return@Button
                    }
                    busy = true
                    onSubmitPassphrase(passphrase, saveForBiometrics) { ok, msg ->
                        busy = false
                        if (ok) onDismiss() else errorMsg = msg
                    }
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isSetupMode) "Enable E2EE" else "Unlock")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDocumentDialog(
    docView: DecryptedDocumentView,
    folders: List<VaultFolder>,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String, tags: List<String>, folderId: String?) -> Unit
) {
    var title by remember { mutableStateOf(docView.displayTitle) }
    var selectedCategory by remember { mutableStateOf(docView.raw.aiCategory) }
    var tagsInput by remember { mutableStateOf(docView.raw.tags.joinToString(", ")) }
    var selectedFolderId by remember { mutableStateOf(docView.raw.folderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Document Details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("AI Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AiCategories.ALL.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (folders.isNotEmpty()) {
                    Text("Folder", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedFolderId.isNullOrBlank(),
                            onClick = { selectedFolderId = null },
                            label = { Text("Unfiled") }
                        )
                        folders.forEach { folder ->
                            FilterChip(
                                selected = selectedFolderId == folder.id,
                                onClick = { selectedFolderId = folder.id },
                                label = { Text(folder.name) },
                                leadingIcon = if (folder.privateVault) {
                                    { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(13.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(title, selectedCategory, tags, selectedFolderId)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddWebsiteDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, urlTemplate: String, category: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Tracking") }

    val quickPresets = listOf(
        Triple("FedEx Track", "https://www.fedex.com/fedextrack/?trknbr={query}", "Tracking"),
        Triple("USPS Track", "https://tools.usps.com/go/TrackConfirmAction?tLabels={query}", "Tracking"),
        Triple("UPS Track", "https://www.ups.com/track?tracknum={query}", "Tracking"),
        Triple("Amazon Orders", "https://www.amazon.com/gp/your-account/order-history?search={query}", "Shopping"),
        Triple("Wikipedia", "https://en.wikipedia.org/w/index.php?search={query}", "Reference")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Lookup Website") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Use {query} where highlighted document text should be inserted (e.g. tracking numbers, order IDs, portals).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Quick Presets", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickPresets.forEach { (pName, pUrl, pCat) ->
                        AssistChip(
                            onClick = {
                                name = pName
                                urlTemplate = pUrl
                                category = pCat
                            },
                            label = { Text(pName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Website Name") },
                    placeholder = { Text("e.g., My Courier Portal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = urlTemplate,
                    onValueChange = { urlTemplate = it },
                    label = { Text("URL / Search Template") },
                    placeholder = { Text("https://example.com/track?id={query}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), urlTemplate.trim(), category.trim()) },
                enabled = name.trim().isNotEmpty() && urlTemplate.trim().length >= 5
            ) {
                Text("Save Website")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SavedWebsitesDialog(
    websites: List<SavedWebsite>,
    onDismiss: () -> Unit,
    onAddWebsite: (name: String, urlTemplate: String, category: String) -> Unit,
    onDeleteWebsite: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved Lookup Websites")
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add new website")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Highlight any text in a document to launch these websites directly with your selected query.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (websites.isEmpty()) {
                    Text(
                        text = "No websites saved yet. Tap + to add tracking portals, order lookups, or custom sites.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    websites.forEach { site ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(site.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        site.urlTemplate,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDeleteWebsite(site.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete website",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { showAdd = true }) {
                Text("Add Website")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showAdd) {
        AddWebsiteDialog(
            onDismiss = { showAdd = false },
            onSave = { n, u, c ->
                onAddWebsite(n, u, c)
                showAdd = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    profile: UserProfile?,
    docCount: Int,
    darkMode: DarkModeOption,
    biometricAppLock: Boolean,
    e2eeUnlocked: Boolean,
    e2eeFingerprint: String?,
    isBatchOrganizing: Boolean,
    onDismiss: () -> Unit,
    onSetDarkMode: (DarkModeOption) -> Unit,
    onToggleBiometricLock: (Boolean) -> Unit,
    onSetupOrUnlockE2ee: () -> Unit,
    onLockE2ee: () -> Unit,
    onAutoCategorizeAll: () -> Unit,
    onManageWebsites: () -> Unit,
    onSignOut: () -> Unit
) {
    val storageBytes = profile?.storageUsedBytes ?: 0L
    val quotaBytes = 500L * 1024L * 1024L // 500 MB visual off-device vault meter
    val progress = (storageBytes.toFloat() / quotaBytes.toFloat()).coerceIn(0f, 1f)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Vault Settings & Cloud Sync", style = MaterialTheme.typography.headlineSmall)

            // Account & Off-Device Cloud Storage Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile?.displayName?.ifBlank { "Google Account" } ?: "Google Account",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = profile?.email.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Off-Device Cloud Storage", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${DocumentProcessor.formatBytes(storageBytes)} · $docCount docs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceAtLeast(0.03f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            // Dark Mode Selector
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dark_mode_toggle")
                ) {
                    DarkModeOption.entries.forEachIndexed { idx, opt ->
                        SegmentedButton(
                            selected = darkMode == opt,
                            onClick = { onSetDarkMode(opt) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = DarkModeOption.entries.size
                            )
                        ) {
                            Text(opt.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }

            // Biometric App Lock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Biometric Login Lock", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Require fingerprint, face, or device PIN on app launch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = biometricAppLock,
                    onCheckedChange = onToggleBiometricLock,
                    modifier = Modifier.testTag("biometric_lock_toggle")
                )
            }

            // End-to-End Encryption Vault Controls
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (e2eeUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = VaultEncryptedGold
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Private Folder E2EE", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = when {
                                    e2eeUnlocked -> "Unlocked · Key ${e2eeFingerprint.orEmpty()}"
                                    profile?.e2eeEnabled == true -> "Locked · AES-256-GCM active"
                                    else -> "Not configured yet"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (e2eeUnlocked) {
                        TextButton(onClick = onLockE2ee) { Text("Lock Now") }
                    } else {
                        TextButton(onClick = onSetupOrUnlockE2ee) {
                            Text(if (profile?.e2eeEnabled == true) "Unlock" else "Set Up")
                        }
                    }
                }
            }

            // AI Batch Categorize + Saved Websites
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onAutoCategorizeAll,
                    enabled = !isBatchOrganizing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isBatchOrganizing) "Organizing…" else "AI Categorize")
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onManageWebsites()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Websites")
                }
            }

            HorizontalDivider()

            TextButton(
                onClick = {
                    onDismiss()
                    onSignOut()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Sign Out", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
