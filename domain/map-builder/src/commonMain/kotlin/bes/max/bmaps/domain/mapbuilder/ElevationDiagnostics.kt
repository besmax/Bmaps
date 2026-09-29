package bes.max.bmaps.domain.mapbuilder

object ElevationDiagnostics {
    fun info(message: String) {
        println("[BmapsElevation] INFO $message")
    }

    fun error(message: String, cause: Throwable? = null) {
        println("[BmapsElevation] ERROR $message")
        cause?.printStackTrace()
    }
}
