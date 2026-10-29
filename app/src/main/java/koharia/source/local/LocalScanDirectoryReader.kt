package koharia.source.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.io.IOException

internal data class LocalScanFile(
    val file: UniFile,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** A scan-scoped snapshot; never reused after refresh or used for file mutations. */
internal class LocalScanDirectoryReader(private val context: Context, private val allowPendingRemote: Boolean = false) {
    val attributes = mutableMapOf<Uri, LocalScanFile>()
    private val directories = mutableMapOf<Uri, List<LocalScanFile>>()
    private val treeUris = mutableMapOf<Uri, Uri>()

    fun metadata(file: UniFile): LocalScanFile = attributes.getOrPut(file.uri) {
        LocalScanFile(file, file.name.orEmpty(), file.isDirectory, file.length(), file.lastModified())
    }

    fun list(directory: UniFile): List<LocalScanFile> = directories.getOrPut(directory.uri) {
        val uri = directory.uri
        val treeUri = uri.takeIf { it.isSafTreeUri() } ?: treeUris[uri]
        if (treeUri == null && !metadata(directory).directory) {
            throw IOException("Local scan target is not a directory")
        }
        if (allowPendingRemote && directory is com.hippo.unifile.RemoteStorageFile &&
            directory.runtime.cachedChildren(directory.storagePath) == null
        ) {
            return@getOrPut emptyList()
        }
        val files = if (treeUri != null) {
            treeUris[directory.uri] = treeUri
            listDocuments(uri, treeUri)
        } else {
            directory.listFiles()?.map(::metadata) ?: throw IOException("Unable to list local directory")
        }
        files.forEach { attributes[it.file.uri] = it }
        files
    }

    private fun isDirectoryEntry(
        treeUri: Uri,
        documentId: String,
        mimeType: String?,
        file: UniFile,
    ): Boolean {
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR || file.isDirectory) return true
        if (!shouldProbeDirectoryChildren(mimeType)) return false
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    internal companion object {
        fun shouldProbeDirectoryChildren(mimeType: String?): Boolean =
            mimeType.isNullOrBlank() ||
                mimeType.equals("application/octet-stream", ignoreCase = true)
    }

    private fun listDocuments(uri: Uri, treeUri: Uri): List<LocalScanFile> {
        val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw IOException("Local directory listing is still loading")
            }
            if (cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null) {
                throw IOException("Local directory provider reported an error")
            }
            val expectedCount = cursor.count
            val files = buildList {
                while (cursor.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    val file = UniFile.fromUri(context, childUri)
                        ?: throw IOException("Unable to resolve local directory entry")
                    val mimeType = cursor.getString(2)
                    val directory = isDirectoryEntry(
                        treeUri = treeUri,
                        documentId = cursor.getString(0),
                        mimeType = mimeType,
                        file = file,
                    )
                    if (directory) treeUris[childUri] = treeUri
                    add(
                        LocalScanFile(
                            file = file,
                            name = cursor.getString(1) ?: throw IOException("Missing local entry name"),
                            directory = directory,
                            sizeBytes = if (directory) -1L else cursor.getLong(3),
                            modifiedAt = cursor.getLong(4),
                        ),
                    )
                }
            }
            if (files.size != expectedCount) throw IOException("Incomplete local directory listing")
            if (files.isNotEmpty()) {
                files
            } else {
                // Some SAF providers report an empty child cursor for a readable directory.
                // Keep DocumentsContract as the primary path, but recover through UniFile when
                // the provider exposes the same directory through its compatibility API.
                UniFile.fromUri(context, uri)?.listFiles()?.map(::metadata).orEmpty()
            }
        } ?: throw IOException("Unable to query local directory")
    }
}
