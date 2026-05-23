package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class LocalModelManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `model directory uses external app storage when it can be created`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(externalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is unusable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        externalRoot.resolve("models").writeText("blocking file")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is not writable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")
        val externalModelDir = externalRoot.resolve("models")

        val dir = LocalModelManager.resolveUsableModelDir(
            externalRoot = externalRoot,
            internalRoot = internalRoot,
            canWriteDirectory = { candidate -> candidate != externalModelDir },
        )

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external root is missing`() {
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(null, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `managed model validation requires exact size and verified metadata`() {
        val bytes = "abc".toByteArray()
        val model = LocalModelManager.ModelInfo(
            id = "tiny",
            displayName = "Tiny",
            url = "https://example.invalid/tiny.litertlm",
            revision = "test-revision",
            fileName = "tiny.litertlm",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            minRamGb = 1,
        )
        val dir = temporaryFolder.newFolder("models")
        val modelFile = dir.resolve(model.fileName)
        val metadataFile = dir.resolve("${model.fileName}.metadata.json")

        modelFile.writeBytes(bytes)

        assertFalse(LocalModelManager.isValidManagedModelFile(modelFile, model))

        metadataFile.writeText(
            """{"modelId":"${model.id}","url":"${model.url}","revision":"${model.revision}","expectedSizeBytes":${model.sizeBytes},"sha256":"bad","checksumVerified":true}"""
        )
        assertFalse(LocalModelManager.isValidManagedModelFile(modelFile, model))

        metadataFile.writeText(
            """{"modelId":"${model.id}","url":"${model.url}","revision":"${model.revision}","expectedSizeBytes":${model.sizeBytes},"sha256":"${model.sha256}","checksumVerified":true}"""
        )
        assertTrue(LocalModelManager.isValidManagedModelFile(modelFile, model))

        modelFile.appendText("d")
        assertFalse(LocalModelManager.isValidManagedModelFile(modelFile, model))
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
