package com.github.senocak.prreview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Failures worth interrupting for.
 *
 * A notification rather than a dialog on the refresh path only: refresh runs unattended, often on
 * a keypress, and a modal dialog for a list that is already showing an error in its status bar is
 * worse than the failure. The review path does use a dialog, because a review that silently does
 * not appear is indistinguishable from one that was never asked for.
 */
object PluginNotifications {
    /** Must match the notificationGroup id in plugin.xml exactly, or the lookup throws at runtime. */
    private const val GROUP_ID = "LiteLLM PR Review"

    fun error(project: Project?, message: String) =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
}
