package com.opc.baize.agent

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NativeAgentRuntimeTest {
    @Test fun continuedLearningAcceptsAnAndroidAppDirectoryAlias() {
        val app = RuntimeEnvironment.getApplication()
        val parent = java.nio.file.Files.createTempDirectory("we-app-alias").toFile()
        try {
            val real = java.io.File(parent, "real").apply { mkdirs() }
            val alias = java.io.File(parent, "alias")
            java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
            val context = object : android.content.ContextWrapper(app) {
                override fun getNoBackupFilesDir() = alias
            }
            LearningLoopContract.verify(context,
                render = { source, output -> output.writeText("PDF fixture: " + source.readText()) }, validatePdf = false)
        } finally { parent.deleteRecursively() }
    }
    @Test fun feedbackCreatesANewArtifactAndPreservesOriginalInk() = LearningLoopContract.verify(
        RuntimeEnvironment.getApplication(),
        // Robolectric 4.13 has no PdfDocument/PdfRenderer shadows; Android uses the actual renderer.
        render = { source, output -> output.writeText("PDF fixture: " + source.readText()) }, validatePdf = false)
    @Test fun skillMcpAndWorkspaceRunInsideAndroid() = NativeRuntimeContract.verify(RuntimeEnvironment.getApplication())
}
