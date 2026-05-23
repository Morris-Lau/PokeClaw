package io.agents.pokeclaw.agent.llm

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ModelDownloadRepositoryTest {

    @Test
    fun `maps running work progress into download state`() {
        val progress = Data.Builder()
            .putLong(ModelDownloadRepository.KEY_BYTES_DOWNLOADED, 42L)
            .putLong(ModelDownloadRepository.KEY_TOTAL_BYTES, 100L)
            .putLong(ModelDownloadRepository.KEY_BYTES_PER_SECOND, 7L)
            .build()

        val state = ModelDownloadRepository.stateFromWorkInfoForTest(
            workInfo = workInfo(WorkInfo.State.RUNNING, progress = progress),
            modelAvailable = false,
        )

        assertEquals(ModelDownloadStatus.RUNNING, state.status)
        assertEquals(42L, state.bytesDownloaded)
        assertEquals(100L, state.totalBytes)
        assertEquals(42, state.progressPercent)
        assertEquals(7L, state.bytesPerSecond)
    }

    @Test
    fun `maps missing work to idle unless model is already available`() {
        assertEquals(
            ModelDownloadStatus.IDLE,
            ModelDownloadRepository.stateFromWorkInfoForTest(null, modelAvailable = false).status
        )
        assertEquals(
            ModelDownloadStatus.SUCCEEDED,
            ModelDownloadRepository.stateFromWorkInfoForTest(null, modelAvailable = true).status
        )
    }

    @Test
    fun `maps failed work output error into failed state`() {
        val output = Data.Builder()
            .putString(ModelDownloadRepository.KEY_ERROR, "checksum mismatch")
            .build()

        val state = ModelDownloadRepository.stateFromWorkInfoForTest(
            workInfo = workInfo(WorkInfo.State.FAILED, output = output),
            modelAvailable = false,
        )

        assertEquals(ModelDownloadStatus.FAILED, state.status)
        assertEquals("checksum mismatch", state.error)
    }

    private fun workInfo(
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ): WorkInfo {
        return WorkInfo(
            UUID.randomUUID(),
            state,
            emptySet(),
            output,
            progress,
        )
    }
}
