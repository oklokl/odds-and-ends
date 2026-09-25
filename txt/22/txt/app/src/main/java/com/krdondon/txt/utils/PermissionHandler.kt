package com.krdondon.txt.utils

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher

/**
 * 초보자용 단순 구현:
 * - 이 앱은 기본적으로 "앱 전용 폴더"에 저장하므로(Scoped Storage) 권한이 필요하지 않습니다.
 * - 기존 코드 구조를 유지하기 위해 '권한 체크'는 항상 true로 두었습니다.
 *
 * 만약 "공용 다운로드 폴더" 등에 직접 저장하려면 SAF(Storage Access Framework) 방식으로 구현하는 것을 추천합니다.
 */
object PermissionHandler {

    fun hasStoragePermission(context: Context): Boolean {
        // 앱 전용 저장소 사용: 별도 권한 불필요
        return true
    }

    fun requestPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        // 앱 전용 저장소 사용: 별도 권한 불필요
        // 필요 시 아래처럼 요청하도록 변경할 수 있습니다(구형 기기용).
        if (Build.VERSION.SDK_INT < 29) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
