package koharia.connection

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionProfileManagerTest {
    @Test
    fun `successful removal releases the directory reservation`() = runTest {
        val storage = TestStorage()
        val changes = ConnectionIdentityChanges(storage, Json)
        val provider = mockk<ConnectionProvider> {
            every { id } returns "test"
            every { isConnectionNameAvailable(any()) } returns true
            every { directoryNameFor(any()) } answers { firstArg<String>() }
            coEvery { removeConnection(any()) } returns Result.success(true)
        }
        val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json).apply {
            setProfiles(listOf(LibraryConnectionProfile(1, "test", "Home")))
        }
        val manager = ConnectionProfileManager(
            preferences,
            ConnectionRegistry(listOf(provider)),
            mockk(relaxed = true),
            changes,
        )

        manager.remove(1).getOrThrow()

        assertEquals(emptyList<LibraryConnectionProfile>(), changes.reservedProfiles())
    }

    @Test
    fun `failed removal keeps the directory reservation`() = runTest {
        val storage = TestStorage()
        val changes = ConnectionIdentityChanges(storage, Json)
        val provider = mockk<ConnectionProvider> {
            every { id } returns "test"
            every { isConnectionNameAvailable(any()) } returns true
            every { directoryNameFor(any()) } answers { firstArg<String>() }
            coEvery { removeConnection(any()) } returns Result.failure(IllegalStateException("busy"))
        }
        val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json).apply {
            setProfiles(listOf(LibraryConnectionProfile(1, "test", "Home")))
        }
        val manager = ConnectionProfileManager(
            preferences,
            ConnectionRegistry(listOf(provider)),
            mockk(relaxed = true),
            changes,
        )

        manager.remove(1)

        assertEquals(listOf(LibraryConnectionProfile(1, "test", "Home")), changes.reservedProfiles())
    }

    private class TestStorage : SharedConfigStorage {
        private val values = mutableMapOf<String, Any?>()
        override fun values(): Map<String, Any?> = values.toMap()
        override fun commit(values: Map<String, Any?>): Boolean {
            this.values.putAll(values)
            return true
        }
    }
}
