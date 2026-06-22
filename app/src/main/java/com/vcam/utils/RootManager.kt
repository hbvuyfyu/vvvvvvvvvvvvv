package com.vcam.utils

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootManager {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    fun isRooted(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            false
        }
    }

    fun runCommand(command: String): ShellResult {
        return try {
            val result = Shell.cmd(command).exec()
            ShellResult(
                success = result.isSuccess,
                output = result.out.joinToString("\n"),
                error = result.err.joinToString("\n")
            )
        } catch (e: Exception) {
            ShellResult(success = false, output = "", error = e.message ?: "Unknown error")
        }
    }

    fun runCommands(vararg commands: String): Boolean {
        return try {
            val result = Shell.cmd(*commands).exec()
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    suspend fun runCommandAsync(command: String): ShellResult = withContext(Dispatchers.IO) {
        runCommand(command)
    }

    fun checkV4L2Loopback(): Boolean {
        val result = runCommand("ls /dev/video* 2>/dev/null")
        return result.success && result.output.isNotBlank()
    }

    fun loadV4L2Loopback(): Boolean {
        val result = runCommand("modprobe v4l2loopback devices=1 video_nr=10 card_label='VirtualCamera' exclusive_caps=1 2>/dev/null || true")
        return checkV4L2Loopback()
    }

    fun getVideoDevices(): List<String> {
        val result = runCommand("ls /dev/video* 2>/dev/null")
        return if (result.success && result.output.isNotBlank()) {
            result.output.trim().split("\n").filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    fun setSystemProp(key: String, value: String): Boolean {
        val result = runCommand("setprop $key $value")
        return result.success
    }

    fun grantPermission(packageName: String, permission: String): Boolean {
        val result = runCommand("pm grant $packageName $permission 2>/dev/null || appops set $packageName CAMERA allow")
        return true
    }

    fun killApp(packageName: String) {
        runCommand("am force-stop $packageName")
    }

    data class ShellResult(
        val success: Boolean,
        val output: String,
        val error: String
    )
}
