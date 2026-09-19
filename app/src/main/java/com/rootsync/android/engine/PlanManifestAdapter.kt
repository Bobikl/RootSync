package com.rootsync.android.engine

/** Lossless snapshot conversion; time filtering and safety checks belong to SyncPlanner. */
fun DirectoryManifest.toPlanTree(): PlanTree = PlanTree(
    exists = !missingRoot,
    strict = strictHashes,
    entries = entries.map { entry ->
        PlanFile(
            path = entry.relativePath,
            kind = when (entry.kind) {
                ManifestKind.DIRECTORY -> PlanFileKind.DIRECTORY
                ManifestKind.FILE -> PlanFileKind.FILE
                ManifestKind.SYMLINK -> PlanFileKind.SYMLINK
            },
            size = entry.size,
            seconds = entry.mtimeSeconds,
            nanos = entry.mtimeNanos,
            hash = entry.sha256,
            linkTarget = entry.symlinkTarget
        )
    }
)