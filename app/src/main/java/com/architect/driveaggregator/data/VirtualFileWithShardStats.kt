package com.architect.driveaggregator.data

import androidx.room.Embedded

data class VirtualFileWithShardStats(
    @Embedded val virtualFile: VirtualFileEntity,
    val totalShardsCount: Int,
    val completedShardsCount: Int
)
