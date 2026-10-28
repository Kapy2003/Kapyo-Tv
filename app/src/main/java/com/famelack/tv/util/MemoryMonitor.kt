package com.famelack.tv.util

import android.os.Debug
import android.util.Log

object MemoryMonitor {
    private const val TAG = "MemoryMonitor"
    private const val TARGET_MAX_HEAP_MB = 80L

    fun logHeapStats(contextTag: String) {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        val nativeAlloc = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val totalAppMem = usedMem + nativeAlloc

        Log.i(
            TAG,
            "[$contextTag] Dalvik Used: ${usedMem}MB / Max: ${maxMem}MB | Native: ${nativeAlloc}MB | Estimated Total: ${totalAppMem}MB"
        )

        if (totalAppMem > TARGET_MAX_HEAP_MB) {
            Log.w(
                TAG,
                "[$contextTag] WARNING: Total heap (${totalAppMem}MB) exceeds the 512MB RAM device target budget (${TARGET_MAX_HEAP_MB}MB)!"
            )
        }
    }

    fun getTotalAllocatedMb(): Long {
        val runtime = Runtime.getRuntime()
        val dalvikUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val nativeAlloc = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        return dalvikUsed + nativeAlloc
    }
}

