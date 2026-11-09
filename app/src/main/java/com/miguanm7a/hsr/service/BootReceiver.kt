package com.miguanm7a.hsr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.miguanm7a.hsr.deploy.DeployScripts
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.deploy.SharedPaths

/**
 * 开机自启：用户勾选后，重启手机自动把上一次选择的任务跑起来。
 *
 * 只在「已部署 + 允许开机自启」时启动；任务命令与界面里点「运行」完全一致，
 * 所以 `after_finish: Loop` / `scheduled_time` 的定时循环照样生效。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val settings = DeploySettings(app)
        if (!settings.isDeployed() || !settings.autoStartOnBoot()) {
            Log.i(TAG, "开机自启未启用或尚未部署，跳过")
            return
        }
        // 接收器生命周期很短，用 goAsync 撑住启动过程
        val pending = goAsync()
        try {
            val binds = DeployScripts.bindArgs(SharedPaths.guestProjectDir(app))
            val task = settings.task()
            val command = DeployScripts.runTaskCommand(
                alias = settings.containerAlias(),
                task = task,
                afterFinish = settings.afterFinish(),
                logLevel = settings.logLevel(),
                binds = binds,
            )
            if (!TaskRunner.isRunning() && TaskRunner.start(app, command, task.label)) {
                TaskKeepAliveService.start(app, task.label)
                Log.i(TAG, "开机自启：已启动 ${task.label}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "开机自启失败", t)
        } finally {
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "M7A-Boot"
    }
}
