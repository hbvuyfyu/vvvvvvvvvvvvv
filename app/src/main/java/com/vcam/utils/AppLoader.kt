package com.vcam.utils

import android.content.Context
import android.content.pm.PackageManager
import com.vcam.model.AppInfo

object AppLoader {

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        return packages
            .filter { pkg -> pkg.packageName != context.packageName }
            .map { pkg ->
                val useCamera = pkg.requestedPermissions?.any { perm ->
                    perm == "android.permission.CAMERA"
                } ?: false

                val isSystem = (pkg.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                AppInfo(
                    appName = pkg.applicationInfo.loadLabel(pm).toString(),
                    packageName = pkg.packageName,
                    icon = pkg.applicationInfo.loadIcon(pm),
                    useCamera = useCamera,
                    isSystem = isSystem
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.useCamera }.thenBy { it.appName })
    }

    fun getCameraApps(context: Context): List<AppInfo> {
        return getInstalledApps(context).filter { it.useCamera }
    }

    fun getUserApps(context: Context): List<AppInfo> {
        return getInstalledApps(context).filter { !it.isSystem }
    }
}
