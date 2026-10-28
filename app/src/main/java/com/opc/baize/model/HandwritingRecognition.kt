package com.opc.baize.model

/** Try local recognition first. The selected fallback owns errors; never silently switch billing routes. */
object HandwritingRecognition {
    fun recognize(local: () -> String, unavailable: () -> String?): String? = try {
        local()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); throw e
    } catch (e: Exception) {
        unavailable()
    }
}
