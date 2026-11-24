package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

enum class FileCategory {
    IMAGE, PDF, DOCX, ARCHIVE, TEXT, OTHER
}

data class ProcessedFilePayload(
    val fileName: String,
    val mimeType: String,
    val fileCategory: FileCategory,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val thumbnailBase64: String,
    val cloudBlobBytes: ByteArray?,
    val cloudBlobMimeType: String,
    val localTextExtraction: String,
    val ocrBitmaps: List<Bitmap>,
    val cachedLocalFile: File?
)

/**
 * Multi-format document ingestion & reader engine supporting:
 * - Images (JPG, PNG, WebP, Camera captures)
 * - PDFs (Android PdfRenderer multi-page rendering + thumbnail + bitmap for Gemini OCR)
 * - Word Documents (.docx XML parsing via ZipInputStream + XmlPullParser)
 * - Archives (.zip & .rar native header inspection for RAR4 & RAR5 entries)
 * - Text & generic files
 */
object DocumentProcessor {
    private const val MAX_CLOUD_BLOB_BYTES = 540_000 // Comfortably fits < 900KB base64 inside Firestore 1 MiB limit
    private const val THUMB_MAX_DIM = 360
    private const val OCR_MAX_DIM = 1280
    private const val MAX_EXTRACTED_CHARS = 85_000

    suspend fun processUri(
        context: Context,
        uri: Uri,
        docId: String,
        fallbackName: String = "document"
    ): ProcessedFilePayload = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = fallbackName
        var reportedSize = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) {
                    val queried = cursor.getString(nameIdx)
                    if (!queried.isNullOrBlank()) displayName = queried
                }
                if (sizeIdx >= 0) {
                    reportedSize = cursor.getLong(sizeIdx)
                }
            }
        }

        val detectedMime = resolver.getType(uri) ?: inferMimeFromExtension(displayName)
        val category = classifyFile(displayName, detectedMime)

        val vaultDir = File(context.filesDir, "vault_files").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "").take(10).ifBlank { defaultExtension(category) }
        val localCopy = File(vaultDir, "${docId}.${ext}")

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localCopy).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to read selected file.")

        val actualSize = max(reportedSize, localCopy.length())

        when (category) {
            FileCategory.IMAGE -> processImageFile(localCopy, displayName, detectedMime, actualSize)
            FileCategory.PDF -> processPdfFile(localCopy, displayName, detectedMime, actualSize)
            FileCategory.DOCX -> processDocxFile(localCopy, displayName, detectedMime, actualSize)
            FileCategory.ARCHIVE -> processArchiveFile(localCopy, displayName, detectedMime, actualSize)
            FileCategory.TEXT -> processTextFile(localCopy, displayName, detectedMime, actualSize)
            FileCategory.OTHER -> processGenericFile(localCopy, displayName, detectedMime, actualSize)
        }
    }

    fun getLocalVaultFile(context: Context, docId: String): File? {
        val dir = File(context.filesDir, "vault_files")
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.nameWithoutExtension == docId }
    }

    fun saveCloudBytesToLocalCache(
        context: Context,
        docId: String,
        fileName: String,
        bytes: ByteArray
    ): File {
        val dir = File(context.filesDir, "vault_files").apply { mkdirs() }
        val ext = fileName.substringAfterLast('.', "bin").take(10).ifBlank { "bin" }
        val file = File(dir, "$docId.$ext")
        file.writeBytes(bytes)
        return file
    }

    fun renderPdfPage(file: File, pageIndex: Int, maxWidth: Int = 1280): Pair<Bitmap, Int>? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = renderer.pageCount
                    if (count <= 0) return null
                    val safeIdx = pageIndex.coerceIn(0, count - 1)
                    renderer.openPage(safeIdx).use { page ->
                        val scale = min(2.0f, maxWidth.toFloat() / max(1, page.width).toFloat())
                        val w = max(1, (page.width * scale).toInt())
                        val h = max(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap to count
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun processImageFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val ocrBitmap = decodeSampledBitmap(file, OCR_MAX_DIM)
            ?: throw IllegalStateException("Unsupported or corrupted image file.")
        val thumbBitmap = scaleBitmap(ocrBitmap, THUMB_MAX_DIM)
        val thumbBase64 = compressBitmapToBase64(thumbBitmap, 68)
        if (thumbBitmap !== ocrBitmap) thumbBitmap.recycle()

        val cloudBytes = compressBitmapToTargetBytes(ocrBitmap, MAX_CLOUD_BLOB_BYTES)

        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.IMAGE,
            fileSizeBytes = fileSize,
            pageCount = 1,
            thumbnailBase64 = thumbBase64,
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = "image/jpeg",
            localTextExtraction = "",
            ocrBitmaps = listOf(ocrBitmap),
            cachedLocalFile = file
        )
    }

    private fun processPdfFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val renderedPages = mutableListOf<Bitmap>()
        var pageCount = 1
        var thumbBase64 = ""

        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    val pagesToOcr = min(pageCount, 3)
                    for (idx in 0 until pagesToOcr) {
                        renderer.openPage(idx).use { page ->
                            val scale = min(1.8f, OCR_MAX_DIM.toFloat() / max(1, page.width).toFloat())
                            val w = max(1, (page.width * scale).toInt())
                            val h = max(1, (page.height * scale).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (idx == 0) {
                                val thumb = scaleBitmap(bmp, THUMB_MAX_DIM)
                                thumbBase64 = compressBitmapToBase64(thumb, 68)
                                if (thumb !== bmp) thumb.recycle()
                            }
                            renderedPages.add(bmp)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to metadata-only if password-protected PDF
        }

        val cloudBytes: ByteArray?
        val cloudMime: String
        if (file.length() <= MAX_CLOUD_BLOB_BYTES) {
            cloudBytes = file.readBytes()
            cloudMime = "application/pdf"
        } else if (renderedPages.isNotEmpty()) {
            cloudBytes = compressBitmapToTargetBytes(renderedPages.first(), MAX_CLOUD_BLOB_BYTES)
            cloudMime = "image/jpeg"
        } else {
            cloudBytes = null
            cloudMime = mimeType
        }

        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.PDF,
            fileSizeBytes = fileSize,
            pageCount = max(1, pageCount),
            thumbnailBase64 = thumbBase64,
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = cloudMime,
            localTextExtraction = "",
            ocrBitmaps = renderedPages,
            cachedLocalFile = file
        )
    }

    private fun processDocxFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val extracted = extractDocxText(file).take(MAX_EXTRACTED_CHARS)
        val cloudBytes = if (file.length() <= MAX_CLOUD_BLOB_BYTES) file.readBytes() else null
        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.DOCX,
            fileSizeBytes = fileSize,
            pageCount = max(1, extracted.length / 2200 + 1),
            thumbnailBase64 = "",
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = mimeType,
            localTextExtraction = extracted.ifBlank {
                "Word document ($fileName). No plain XML text could be extracted."
            },
            ocrBitmaps = emptyList(),
            cachedLocalFile = file
        )
    }

    private fun processArchiveFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val entries = if (fileName.lowercase().endsWith(".rar") || mimeType.contains("rar", ignoreCase = true)) {
            inspectRarEntries(file)
        } else {
            inspectZipEntries(file)
        }

        val manifestText = buildString {
            appendLine("Archive: $fileName (${formatBytes(fileSize)})")
            appendLine("Entries detected: ${entries.size}")
            appendLine("---")
            if (entries.isEmpty()) {
                appendLine("Compressed archive detected. Entry names are encrypted or use an unsupported header format.")
            } else {
                entries.take(250).forEach { appendLine("• $it") }
                if (entries.size > 250) {
                    appendLine("... and ${entries.size - 250} more entries")
                }
            }
        }.take(MAX_EXTRACTED_CHARS)

        val cloudBytes = if (file.length() <= MAX_CLOUD_BLOB_BYTES) file.readBytes() else null

        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.ARCHIVE,
            fileSizeBytes = fileSize,
            pageCount = entries.size,
            thumbnailBase64 = "",
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = mimeType,
            localTextExtraction = manifestText,
            ocrBitmaps = emptyList(),
            cachedLocalFile = file
        )
    }

    private fun processTextFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val text = runCatching {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(MAX_EXTRACTED_CHARS)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
        }.getOrDefault("")

        val cloudBytes = if (file.length() <= MAX_CLOUD_BLOB_BYTES) file.readBytes() else null

        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.TEXT,
            fileSizeBytes = fileSize,
            pageCount = 1,
            thumbnailBase64 = "",
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = mimeType,
            localTextExtraction = text,
            ocrBitmaps = emptyList(),
            cachedLocalFile = file
        )
    }

    private fun processGenericFile(
        file: File,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): ProcessedFilePayload {
        val cloudBytes = if (file.length() <= MAX_CLOUD_BLOB_BYTES) file.readBytes() else null
        return ProcessedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            fileCategory = FileCategory.OTHER,
            fileSizeBytes = fileSize,
            pageCount = 1,
            thumbnailBase64 = "",
            cloudBlobBytes = cloudBytes,
            cloudBlobMimeType = mimeType,
            localTextExtraction = "File: $fileName\nType: $mimeType\nSize: ${formatBytes(fileSize)}",
            ocrBitmaps = emptyList(),
            cachedLocalFile = file
        )
    }

    private fun extractDocxText(file: File): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml" || entry.name.startsWith("word/header") || entry.name.startsWith("word/footer")) {
                        val xmlBytes = zis.readBytes()
                        val parser = Xml.newPullParser()
                        parser.setInput(xmlBytes.inputStream(), "UTF-8")
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    when (parser.name) {
                                        "t" -> {
                                            val text = parser.nextText()
                                            sb.append(text)
                                        }
                                        "tab" -> sb.append("\t")
                                        "br" -> sb.append("\n")
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name == "p") sb.append("\n")
                                }
                            }
                            event = parser.next()
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // Fallback for legacy .doc binary: scrape printable ASCII runs
            return extractPrintableStringsFallback(file)
        }
        val result = sb.toString().trim()
        return result.ifBlank { extractPrintableStringsFallback(file) }
    }

    private fun extractPrintableStringsFallback(file: File): String {
        return try {
            val bytes = file.readBytes().take(200_000).toByteArray()
            val out = StringBuilder()
            val current = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 32..126 || c == 10 || c == 9) {
                    current.append(c.toChar())
                } else {
                    if (current.length >= 6) {
                        out.append(current).append(' ')
                    }
                    current.clear()
                }
            }
            if (current.length >= 6) out.append(current)
            out.toString().replace(Regex("\\s{3,}"), "\n").trim().take(20_000)
        } catch (_: Exception) {
            ""
        }
    }

    private fun inspectZipEntries(file: File): List<String> {
        val entries = mutableListOf<String>()
        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && entries.size < 500) {
                    val sizeLabel = if (entry.size >= 0) " (${formatBytes(entry.size)})" else ""
                    entries.add("${entry.name}$sizeLabel")
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // Ignore malformed ZIP entries
        }
        return entries
    }

    /**
     * Pure-Kotlin RAR4 & RAR5 header scanner that lists archived filenames and sizes safely without native binaries.
     */
    private fun inspectRarEntries(file: File): List<String> {
        val results = mutableListOf<String>()
        try {
            val data = file.readBytes()
            if (data.size < 8) return results

            val isRar5 = data.size >= 8 &&
                data[0] == 0x52.toByte() && data[1] == 0x61.toByte() &&
                data[2] == 0x72.toByte() && data[3] == 0x21.toByte() &&
                data[4] == 0x1A.toByte() && data[5] == 0x07.toByte() &&
                data[6] == 0x01.toByte() && data[7] == 0x00.toByte()

            val isRar4 = data.size >= 7 &&
                data[0] == 0x52.toByte() && data[1] == 0x61.toByte() &&
                data[2] == 0x72.toByte() && data[3] == 0x21.toByte() &&
                data[4] == 0x1A.toByte() && data[5] == 0x07.toByte() &&
                data[6] == 0x00.toByte()

            if (isRar5) {
                var pos = 8
                while (pos + 6 < data.size && results.size < 500) {
                    pos += 4 // CRC32
                    val (headerSize, p1) = readVint(data, pos)
                    if (headerSize <= 0 || p1 + headerSize > data.size) break
                    val blockStart = p1
                    val blockEnd = (p1 + headerSize).toInt()
                    val (headerType, p2) = readVint(data, blockStart)
                    val (headerFlags, p3) = readVint(data, p2)
                    var cur = p3
                    if ((headerFlags and 0x01L) != 0L) {
                        val (_, next) = readVint(data, cur) // extra area size
                        cur = next
                    }
                    var dataSize = 0L
                    if ((headerFlags and 0x02L) != 0L) {
                        val (ds, next) = readVint(data, cur)
                        dataSize = ds
                        cur = next
                    }
                    if (headerType == 2L && cur < blockEnd) { // File header
                        val (_, c1) = readVint(data, cur) // file flags
                        val (unpackedSize, c2) = readVint(data, c1)
                        val (_, c3) = readVint(data, c2) // attributes
                        var c4 = c3
                        // skip optional mtime (4 bytes) / crc (4 bytes) if flags set - scan safe name length
                        val (fileFlags, _) = readVint(data, cur)
                        if ((fileFlags and 0x02L) != 0L) c4 += 4
                        if ((fileFlags and 0x04L) != 0L) c4 += 4
                        val (_, c5) = readVint(data, c4) // compression info
                        val (_, c6) = readVint(data, c5) // host OS
                        val (nameLen, c7) = readVint(data, c6)
                        val len = nameLen.toInt()
                        if (len in 1..1024 && c7 + len <= blockEnd) {
                            val name = String(data, c7, len, Charsets.UTF_8)
                            results.add("$name (${formatBytes(unpackedSize)})")
                        }
                    }
                    val nextPos = blockEnd + dataSize.toInt()
                    if (nextPos <= pos) break
                    pos = nextPos
                }
            } else if (isRar4) {
                var pos = 7
                while (pos + 7 <= data.size && results.size < 500) {
                    val headType = data[pos + 2].toInt() and 0xFF
                    val headFlags = readU16LE(data, pos + 3)
                    val headSize = readU16LE(data, pos + 5)
                    if (headSize < 7 || pos + headSize > data.size) break
                    var addSize = 0L
                    if ((headFlags and 0x8000) != 0 || headType == 0x74) {
                        if (pos + 11 <= data.size) {
                            addSize = readU32LE(data, pos + 7)
                        }
                    }
                    if (headType == 0x74 && pos + 32 <= data.size) {
                        val unpSize = readU32LE(data, pos + 11)
                        val nameSize = readU16LE(data, pos + 26)
                        val nameStart = pos + 32
                        if (nameSize in 1..1024 && nameStart + nameSize <= pos + headSize) {
                            val rawName = String(data, nameStart, nameSize, Charsets.UTF_8)
                                .substringBefore('\u0000')
                            if (rawName.isNotBlank()) {
                                results.add("$rawName (${formatBytes(unpSize)})")
                            }
                        }
                    }
                    val step = headSize.toLong() + addSize
                    if (step <= 0 || pos + step > data.size) break
                    pos = (pos + step).toInt()
                }
            }
        } catch (_: Exception) {
            // Best-effort archive inspection
        }
        return results
    }

    private fun readVint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size && shift < 63) {
            val b = data[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to i
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) > maxDim * 2 || (bounds.outHeight / sampleSize) > maxDim * 2) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val scaled = scaleBitmap(decoded, maxDim)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = max(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val w = max(1, (src.width * ratio).toInt())
        val h = max(1, (src.height * ratio).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun compressBitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun compressBitmapToTargetBytes(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = 82
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 12
        } while (bytes.size > maxBytes && quality >= 30)
        return bytes
    }

    fun classifyFile(fileName: String, mimeType: String): FileCategory {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        return when {
            lowerMime.startsWith("image/") ||
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                lowerName.endsWith(".heic") -> FileCategory.IMAGE

            lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> FileCategory.PDF

            lowerMime.contains("wordprocessingml") || lowerMime.contains("msword") ||
                lowerName.endsWith(".docx") || lowerName.endsWith(".doc") -> FileCategory.DOCX

            lowerMime.contains("rar") || lowerMime.contains("zip") || lowerMime.contains("archive") ||
                lowerName.endsWith(".rar") || lowerName.endsWith(".zip") -> FileCategory.ARCHIVE

            lowerMime.startsWith("text/") || lowerName.endsWith(".txt") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".md") -> FileCategory.TEXT

            else -> FileCategory.OTHER
        }
    }

    private fun inferMimeFromExtension(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "rar" -> "application/vnd.rar"
            "zip" -> "application/zip"
            "txt", "md", "csv" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun defaultExtension(category: FileCategory): String = when (category) {
        FileCategory.IMAGE -> "jpg"
        FileCategory.PDF -> "pdf"
        FileCategory.DOCX -> "docx"
        FileCategory.ARCHIVE -> "zip"
        FileCategory.TEXT -> "txt"
        FileCategory.OTHER -> "bin"
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
