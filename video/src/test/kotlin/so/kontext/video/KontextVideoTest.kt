package so.kontext.video

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KontextVideoTest {

    @Test
    fun `exposes a non-blank version`() {
        assertTrue(KontextVideo.version.isNotBlank())
    }
}
