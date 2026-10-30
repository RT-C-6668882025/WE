package com.opc.baize.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAgentDeviceTest {
    @Test fun skillMcpAndWorkspaceRunInTheAppProcess() = NativeRuntimeContract.verify(InstrumentationRegistry.getInstrumentation().targetContext)
}
