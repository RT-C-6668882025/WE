package com.opc.baize.agent

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NativeAgentRuntimeTest {
    @Test fun skillMcpAndWorkspaceRunInsideAndroid() = NativeRuntimeContract.verify(RuntimeEnvironment.getApplication())
}
