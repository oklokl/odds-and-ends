package com.krdonon.timer

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Android 17 메모리 제한으로 이전 프로세스가 종료되었는지 가볍게 진단한다.
 * 진단 정보만 기록하며 별도 권한이나 네트워크 전송은 사용하지 않는다.
 */
object MemoryDiagnostics {
    private const val MEMORY_LIMITER_MARKER = "MemoryLimiter:AnonSwap"

    fun logPreviousMemoryLimiterExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
            val recentExits = activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                5
            )

            val memoryLimiterExit = recentExits.firstOrNull { info ->
                info.reason == android.app.ApplicationExitInfo.REASON_OTHER &&
                    info.description?.contains(MEMORY_LIMITER_MARKER, ignoreCase = false) == true
            }

            if (memoryLimiterExit != null) {
                AppLog.i(
                    context,
                    "MemoryDiagnostics",
                    "previous process exit detected: $MEMORY_LIMITER_MARKER"
                )
            }
        }.onFailure { error ->
            AppLog.e(context, "MemoryDiagnostics", "failed to inspect previous process exit", error)
        }
    }
}
