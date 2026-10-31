package com.opc.baize.agent

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NativeAgentRuntimeTest {
    @Test fun feedbackCreatesANewArtifactAndPreservesOriginalInk() = LearningLoopContract.verify(
        RuntimeEnvironment.getApplication(),
        // Robolectric 4.13 has no PdfDocument/PdfRenderer shadows; Android uses the actual renderer.
        render = { source, output -> output.writeText("PDF fixture: " + source.readText()) }, validatePdf = false)
    @Test fun skillMcpAndWorkspaceRunInsideAndroid() = NativeRuntimeContract.verify(RuntimeEnvironment.getApplication())
}
