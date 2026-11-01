package koharia.source.local

/** Metadata storage is a setup-time choice until an explicit migration exists. */
internal fun canChangeMetadataStorage(setupCompleted: Boolean): Boolean = !setupCompleted
