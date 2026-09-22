package com.dominik.cardvault.data.feedback

import java.io.File

/** ASCII-only legacy IDs and UUIDs; never interpret imported IDs as paths. */
internal object FeedbackPathPolicy {
    fun validateId(id: String): String = id.also {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(it)) { "Ungültige Scan-Feedback-ID." }
    }

    fun file(root: File, directory: String, id: String, extension: String): File {
        validateId(id)
        require(directory in setOf("images", "metadata") && extension in setOf("jpg", "json", "tmp", "importing"))
        val canonicalRoot = root.canonicalFile
        val parent = File(canonicalRoot, directory)
        require(parent.canonicalFile == parent) { "Unzulässige Verknüpfung im Feedbackverzeichnis." }
        val target = File(parent, "$id.$extension")
        require(target.canonicalFile == target && target.parentFile == parent) { "Unzulässiger Feedbackpfad." }
        return target
    }
}
