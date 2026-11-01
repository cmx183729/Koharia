package koharia.source.local

import koharia.connection.ConnectionLibraryKind
import koharia.storage.LibraryStorageMode

internal fun localLibraryKind(mode: LibraryStorageMode): ConnectionLibraryKind = when (mode) {
    LibraryStorageMode.LOCAL -> ConnectionLibraryKind.LOCAL
    LibraryStorageMode.WEBDAV -> ConnectionLibraryKind.WEBDAV
    LibraryStorageMode.SMB -> ConnectionLibraryKind.SMB
}
