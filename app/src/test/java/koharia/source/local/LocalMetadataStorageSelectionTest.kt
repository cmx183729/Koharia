package koharia.source.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalMetadataStorageSelectionTest {

    @Test
    fun `metadata storage can be selected only during setup`() {
        assertTrue(canChangeMetadataStorage(setupCompleted = false))
        assertFalse(canChangeMetadataStorage(setupCompleted = true))
    }
}
