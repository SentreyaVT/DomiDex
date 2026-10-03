package com.dominik.cardvault.cache

import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path

/** Only Coil's replaceable image files; never personal photos, upload staging or evidence. */
internal object DomiDexImageCachePolicy {
    const val MAX_DISK_BYTES = 64L * 1024 * 1024

    // Exactly Coil 3.3's existing singleton directory, so an update reuses its journal
    // instead of retaining a second independent image cache. Keep a single owner.
    fun directory(temporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY): Path =
        temporaryDirectory / "coil3_disk_cache"

    val diskCache: DiskCache by lazy { createDiskCache(directory()) }

    // A separate path lets file tests exercise the real Coil cache without opening
    // the application singleton or deleting any existing application files.
    fun createDiskCache(directory: Path): DiskCache = DiskCache.Builder()
        .directory(directory)
        .maxSizeBytes(MAX_DISK_BYTES)
        .build()

    // Coil trims asynchronously after writes and at shutdown. This is an LRU byte
    // target, not an instantaneous filesystem hard cap: open snapshots/edits,
    // journal/filesystem overhead and an old journal before its next trim can exceed it.
}
