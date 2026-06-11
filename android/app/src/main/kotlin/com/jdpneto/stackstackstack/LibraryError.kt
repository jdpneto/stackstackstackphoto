package com.jdpneto.stackstackstack

/** Library mutation failures surfaced to the editor UI. Mirrors iOS [LibraryError]. */
sealed class LibraryError(message: String) : Exception(message) {
    /** The record with the requested id no longer exists in the index. */
    object RecordMissing : LibraryError("This stack no longer exists in the library.")
}
