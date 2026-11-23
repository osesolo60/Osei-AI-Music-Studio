package com.osei.aimusicstudio.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

enum class DeviceMemoryTier(val label: String) {
    LOW_RAM_4GB("Low-RAM Device (<= 4 GB)"),
    MID_RAM_6GB("Mid-RAM Device (6 GB)"),
    HIGH_RAM_8GB_PLUS("High-RAM Device (8 GB+)")
}

data class MemorySnapshot(
    val deviceTotalMb: Long,
    val deviceAvailableMb: Long,
    val isSystemLowMemory: Boolean,
    val appJvmUsedMb: Long,
    val appJvmMaxMb: Long,
    val appNativeAllocatedMb: Long
) {
    val combinedProcessUsedMb: Long
        get() = appJvmUsedMb + appNativeAllocatedMb
}

data class LowMemoryInferenceConfig(
    val tier: DeviceMemoryTier,
    val chunkSeconds: Float,
    val overlapSeconds: Float,
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val enableMemoryPattern: Boolean,
    val allowHeavyDemucs: Boolean
)

data class InferenceBenchmark(
    val modelId: String,
    val deviceTotalRamMb: Long,
    val baselineUsedMb: Long,
    val afterModelLoadUsedMb: Long,
    val peakUsedMb: Long,
    val totalDurationMs: Long,
    val chunksProcessed: Int,
    val chunkSeconds: Float,
    val oomRecovered: Boolean
)

object MemoryProfiler {
    private const val MB = 1024L * 1024L

    fun captureSnapshot(context: Context): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(memInfo)
        } else {
            memInfo.totalMem = 4096L * MB
            memInfo.availMem = 1500L * MB
            memInfo.lowMemory = false
        }

        val runtime = Runtime.getRuntime()
        val jvmUsed = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val jvmMax = runtime.maxMemory() / MB
        val nativeAllocated = try {
            Debug.getNativeHeapAllocatedSize() / MB
        } catch (_: Throwable) {
            0L
        }

        return MemorySnapshot(
            deviceTotalMb = memInfo.totalMem / MB,
            deviceAvailableMb = memInfo.availMem / MB,
            isSystemLowMemory = memInfo.lowMemory,
            appJvmUsedMb = jvmUsed,
            appJvmMaxMb = jvmMax,
            appNativeAllocatedMb = nativeAllocated
        )
    }

    fun recommendConfig(snapshot: MemorySnapshot): LowMemoryInferenceConfig {
        return when {
            snapshot.deviceTotalMb <= 4600L || snapshot.isSystemLowMemory || snapshot.deviceAvailableMb < 1200L -> {
                LowMemoryInferenceConfig(
                    tier = DeviceMemoryTier.LOW_RAM_4GB,
                    chunkSeconds = 2.0f,
                    overlapSeconds = 0.5f,
                    intraOpThreads = 2,
                    interOpThreads = 1,
                    enableMemoryPattern = false,
                    allowHeavyDemucs = false
                )
            }
            snapshot.deviceTotalMb <= 6600L -> {
                LowMemoryInferenceConfig(
                    tier = DeviceMemoryTier.MID_RAM_6GB,
                    chunkSeconds = 3.0f,
                    overlapSeconds = 0.5f,
                    intraOpThreads = 3,
                    interOpThreads = 1,
                    enableMemoryPattern = false,
                    allowHeavyDemucs = false
                )
            }
            else -> {
                LowMemoryInferenceConfig(
                    tier = DeviceMemoryTier.HIGH_RAM_8GB_PLUS,
                    chunkSeconds = 4.0f,
                    overlapSeconds = 0.5f,
                    intraOpThreads = 4,
                    interOpThreads = 1,
                    enableMemoryPattern = true,
                    allowHeavyDemucs = true
                )
            }
        }
    }
}
