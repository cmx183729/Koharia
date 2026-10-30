package koharia.source.local

import android.content.Context
import android.net.Uri
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.json.Json
import java.util.UUID

internal fun shouldCreateInitialLocalDirectories(
    sourceId: Long,
    config: LocalLibraryConfig,
    profiles: List<LibraryConnectionProfile>,
): Boolean = !config.setupCompleted && config.roots.isEmpty() && profiles.none {
    it.id != sourceId && it.providerId == LocalFolderConnectionProvider.ID
}

internal fun LocalLibraryConfig.initialSetupDraft(isNew: Boolean): LocalLibraryConfig =
    if (isNew && !setupCompleted && roots.isEmpty()) {
        copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS))
    } else {
        this
    }

internal fun LocalLibraryConfig.canUseSelectedDirectory(): Boolean =
    !setupCompleted && roots.isEmpty() && enabledContentTypes.count { it != LocalLibraryContentType.MIXED } == 1

internal fun LocalLibraryConfig.hasSelectedDefaultModes(selectedShelfIds: Collection<String>): Boolean {
    if (setupCompleted) return true
    val types = enabledContentTypes.filter { it != LocalLibraryContentType.MIXED }
    return types.isNotEmpty() && types.all {
        defaultBookshelfId(it).let { id -> id.isNotBlank() && id in selectedShelfIds }
    }
}

internal fun LocalLibraryConfig.enabledLibraryConfiguration(): LocalLibraryConfig {
    val types = enabledContentTypes.filterTo(linkedSetOf()) { it != LocalLibraryContentType.MIXED }
    require(types.isNotEmpty())
    return copy(
        enabledContentTypes = types,
        bookshelves = types.flatMap(::bookshelvesFor),
        roots = roots.filter { it.contentType in types },
    )
}

internal fun prepareInitialLocalDirectories(
    context: Context,
    config: LocalLibraryConfig,
    dataDirectory: String,
    displayPath: String,
    json: Json,
    useSelectedDirectory: Boolean = config.canUseSelectedDirectory(),
): LocalLibraryConfig {
    if (config.setupCompleted) return config
    val missingTypes = config.missingInitialDirectoryTypes()
    if (missingTypes.isEmpty()) return config
    require(dataDirectory.isNotBlank()) { "App data directory is not configured" }
    require(!useSelectedDirectory || config.canUseSelectedDirectory()) {
        "A shared selected directory cannot be used for multiple content types or existing roots"
    }
    if (useSelectedDirectory) {
        return config.withDirectlySelectedRoot(dataDirectory, displayPath, missingTypes.single())
    }
    val layout = checkNotNull(
        createManagedLayout(
            context,
            Uri.parse(dataDirectory),
            displayPath,
            config.libraryId.ifBlank { UUID.randomUUID().toString() },
            missingTypes,
            json,
        ),
    ) { "Unable to create local library directories in app data directory" }
    return config.copy(
        roots = config.roots + layout.roots.map { root ->
            root.copy(bookshelfId = config.defaultBookshelfId(root.contentType).also { require(it.isNotBlank()) })
        },
        managedBaseTreeUri = dataDirectory,
        managedBaseDisplayPath = displayPath,
        libraryId = layout.libraryId,
    )
}

internal fun LocalLibraryConfig.withDirectlySelectedRoot(
    treeUri: String,
    displayPath: String,
    contentType: LocalLibraryContentType,
): LocalLibraryConfig {
    require(!setupCompleted)
    require(contentType != LocalLibraryContentType.MIXED)
    val missingTypes = missingInitialDirectoryTypes()
    require(canUseSelectedDirectory()) { "A selected directory requires one content type and no existing roots" }
    require(contentType in missingTypes)
    require(treeUri.isNotBlank())
    val bookshelfId = defaultBookshelfId(contentType)
    require(bookshelfId.isNotBlank())
    return copy(
        roots = roots + LocalLibraryRootConfig(
            id = UUID.randomUUID().toString(),
            treeUri = treeUri,
            displayPath = displayPath,
            contentType = contentType,
            bookshelfId = bookshelfId,
            relativePath = "",
            managed = false,
        ),
        libraryId = libraryId.ifBlank { UUID.randomUUID().toString() },
    )
}

internal fun LocalLibraryConfig.missingInitialDirectoryTypes(): Set<LocalLibraryContentType> =
    if (setupCompleted) {
        emptySet()
    } else {
        enabledContentTypes.filterTo(linkedSetOf()) { type ->
            type != LocalLibraryContentType.MIXED && roots.none { it.contentType == type }
        }
    }

internal suspend fun prepareInitialNetworkDirectories(
    backend: koharia.storage.LibraryStorageBackend,
    config: LocalLibraryConfig,
    useSelectedDirectory: Boolean = config.canUseSelectedDirectory(),
): LocalLibraryConfig {
    val missingTypes = config.missingInitialDirectoryTypes()
    if (missingTypes.isEmpty()) return config
    if (useSelectedDirectory) {
        require(config.canUseSelectedDirectory()) {
            "A selected directory requires one content type and no existing roots"
        }
        check(backend.stat("").directory)
        val type = missingTypes.single()
        return config.copy(
            roots = config.roots + LocalLibraryRootConfig(
                id = UUID.randomUUID().toString(),
                contentType = type,
                bookshelfId = config.defaultBookshelfId(type).also { require(it.isNotBlank()) },
                relativePath = "",
                managed = false,
            ),
        )
    }
    val roots = missingTypes.map { type ->
        val name = if (type == LocalLibraryContentType.COMICS) "Comics" else "Books"
        val existing = try {
            backend.stat(name)
        } catch (error: koharia.storage.StorageFailure) {
            if (error.reason != koharia.storage.StorageFailure.Reason.NOT_FOUND) throw error
            null
        }
        if (existing != null) {
            if (!existing.directory) {
                throw koharia.storage.StorageFailure(
                    koharia.storage.StorageFailure.Reason.CONFLICT,
                )
            }
        } else {
            createStorageDirectory(backend, "", name)
        }
        LocalLibraryRootConfig(
            id = UUID.randomUUID().toString(),
            displayPath = name,
            contentType = type,
            bookshelfId = config.defaultBookshelfId(type).also { require(it.isNotBlank()) },
            relativePath = name,
            managed = true,
        )
    }
    return config.copy(roots = config.roots + roots)
}
