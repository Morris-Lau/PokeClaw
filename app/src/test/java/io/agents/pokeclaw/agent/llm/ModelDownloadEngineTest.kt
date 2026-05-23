package io.agents.pokeclaw.agent.llm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelDownloadEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resumes partial download with HTTP range response`() {
        val modelDir = temporaryFolder.newFolder("models")
        val content = "abcdef".toByteArray()
        val model = tinyModel(content)
        partialFile(modelDir, model).writeBytes("abc".toByteArray())
        sidecarFile(modelDir, model).writeText(sidecarJson(model))
        server.enqueue(headResponse(content.size))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("Content-Length", 3)
                .setBody("def")
        )

        val outputPath = engine().download(modelDir, model, NoopDownloadListener)

        assertEquals(content.decodeToString(), File(outputPath).readText())
        assertFalse(partialFile(modelDir, model).exists())
        assertTrue(finalSidecarFile(modelDir, model).exists())
        assertEquals("HEAD", server.takeRequest().method)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("bytes=3-", get.getHeader("Range"))
    }

    @Test
    fun `server ignoring range restarts download instead of appending duplicate bytes`() {
        val modelDir = temporaryFolder.newFolder("models")
        val content = "abcdef".toByteArray()
        val model = tinyModel(content)
        partialFile(modelDir, model).writeBytes("abc".toByteArray())
        sidecarFile(modelDir, model).writeText(sidecarJson(model))
        server.enqueue(headResponse(content.size))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", content.size)
                .setBody(content.decodeToString())
        )

        val outputPath = engine().download(modelDir, model, NoopDownloadListener)

        assertEquals(content.decodeToString(), File(outputPath).readText())
        val get = server.takeRequest().also { assertEquals("HEAD", it.method) }.let { server.takeRequest() }
        assertEquals("bytes=3-", get.getHeader("Range"))
    }

    @Test
    fun `partial metadata mismatch discards partial and starts from zero`() {
        val modelDir = temporaryFolder.newFolder("models")
        val content = "abcdef".toByteArray()
        val model = tinyModel(content)
        partialFile(modelDir, model).writeText("abc")
        sidecarFile(modelDir, model).writeText("""{"modelId":"old-model"}""")
        server.enqueue(headResponse(content.size))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", content.size)
                .setBody(content.decodeToString())
        )

        val outputPath = engine().download(modelDir, model, NoopDownloadListener)

        assertEquals(content.decodeToString(), File(outputPath).readText())
        server.takeRequest()
        val get = server.takeRequest()
        assertNull(get.getHeader("Range"))
    }

    @Test
    fun `range not satisfiable completes from existing verified partial`() {
        val modelDir = temporaryFolder.newFolder("models")
        val content = "abcdef".toByteArray()
        val model = tinyModel(content)
        partialFile(modelDir, model).writeBytes(content)
        sidecarFile(modelDir, model).writeText(sidecarJson(model))
        server.enqueue(headResponse(content.size))
        server.enqueue(MockResponse().setResponseCode(416))

        val outputPath = engine().download(modelDir, model, NoopDownloadListener)

        assertEquals(content.decodeToString(), File(outputPath).readText())
        assertFalse(partialFile(modelDir, model).exists())
        assertTrue(finalSidecarFile(modelDir, model).readText().contains("checksumVerified"))
    }

    private fun engine(): ModelDownloadEngine {
        return ModelDownloadEngine(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            nowMs = { 1234L },
        )
    }

    private fun tinyModel(bytes: ByteArray): LocalModelManager.ModelInfo {
        return LocalModelManager.ModelInfo(
            id = "tiny",
            displayName = "Tiny",
            url = server.url("/tiny.litertlm").toString(),
            revision = "test-revision",
            fileName = "tiny.litertlm",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            minRamGb = 1,
        )
    }

    private fun partialFile(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.downloading")

    private fun sidecarFile(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.downloading.json")

    private fun finalSidecarFile(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.metadata.json")

    private fun sidecarJson(model: LocalModelManager.ModelInfo): String =
        """{"modelId":"${model.id}","url":"${model.url}","revision":"${model.revision}","expectedSizeBytes":${model.sizeBytes},"sha256":"${model.sha256}"}"""

    private fun headResponse(size: Int): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Length", size)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("ETag", "\"tiny-etag\"")
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private object NoopDownloadListener : ModelDownloadListener {
        override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) = Unit
    }
}
