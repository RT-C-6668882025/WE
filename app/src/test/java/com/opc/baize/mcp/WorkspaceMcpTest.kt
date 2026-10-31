package com.opc.baize.mcp

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceMcpTest {
    @Test fun httpWorkspaceSkillAndContinuedPages() = McpContract.verify(RuntimeEnvironment.getApplication(), false)
}
