package com.opc.baize.model

/** Local first by default. An explicitly selected vision mode skips local download waits. */
object HandwritingRecognition {
    fun recognize(local: () -> String, unavailable: () -> String?, directVision: Boolean = false): String? {
        if (directVision) return unavailable()
        return try { local() }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: Exception) { unavailable() }
    }
}
