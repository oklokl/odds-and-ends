package com.krdonon.microphone.utils

import android.content.Context
import android.util.Log
import java.io.File

object CacheCleaner {

    private const val TAG = "CacheCleaner"

    // 캐시 전체 허용 최대 용량 (3GB 정도에서 미리 정리)
    private const val MAX_CACHE_SIZE_BYTES = 3L * 1024 * 1024 * 1024 // 3GB

    // 최소 확보하고 싶은 여유 용량
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024 // 500MB

    fun cleanRecordingCache(context: Context) {
        val cacheDir: File = context.cacheDir ?: return

        val files = cacheDir.listFiles { file ->
            // 녹음 임시 파일만 타겟팅
            file.name.startsWith("temp_recording_")
        }?.sortedBy { it.lastModified() } ?: return

        var totalSize = files.sumOf { it.length() }
        var usableSpace = cacheDir.usableSpace

        Log.d(TAG, "before clean: cacheSize=$totalSize, free=$usableSpace")

        if (totalSize == 0L && usableSpace > MIN_FREE_BYTES) return

        for (f in files) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES && usableSpace > MIN_FREE_BYTES) break

            val size = f.length()
            if (f.delete()) {
                totalSize -= size
                usableSpace = cacheDir.usableSpace
                Log.d(TAG, "deleted cache file=${f.name}, size=$size")
            }
        }

        Log.d(TAG, "after clean: cacheSize=$totalSize, free=$usableSpace")
    }
}
