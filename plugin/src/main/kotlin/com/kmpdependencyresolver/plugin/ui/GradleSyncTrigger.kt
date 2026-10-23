package com.kmpdependencyresolver.plugin.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants

/** How a Gradle sync is started after Add; see [GradleSyncTrigger.select]. */
enum class SyncMethod { ANDROID_STUDIO_ACTION, GRADLE_REFRESH }

/**
 * Starts a Gradle sync. Android Studio drives sync through its own `Android.SyncProject` action; there the
 * platform's `ExternalSystem.RefreshAllProjects` action is registered but does not sync, which is why the
 * notification button previously did nothing. IntelliJ IDEA uses the public external-system refresh API.
 */
class GradleSyncTrigger(private val project: Project) {
    /** Must be called on the EDT. Returns false when no sync mechanism could be started. */
    fun sync(): Boolean = try {
        when (select { ActionManager.getInstance().getAction(it) != null }) {
            SyncMethod.ANDROID_STUDIO_ACTION -> {
                val action = ActionManager.getInstance().getAction(ANDROID_SYNC_ACTION)
                val context = DataContext { dataId -> if (CommonDataKeys.PROJECT.`is`(dataId)) project else null }
                val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.NOTIFICATION, ActionUiKind.NONE, null)
                ActionUtil.performAction(action, event)
            }
            SyncMethod.GRADLE_REFRESH -> ExternalSystemUtil.refreshProjects(ImportSpecBuilder(project, GradleConstants.SYSTEM_ID))
        }
        true
    } catch (exception: RuntimeException) {
        LOG.warn("Could not start Gradle sync", exception)
        false
    }

    companion object {
        const val ANDROID_SYNC_ACTION = "Android.SyncProject"
        private val LOG = Logger.getInstance(GradleSyncTrigger::class.java)

        fun select(isActionRegistered: (String) -> Boolean): SyncMethod =
            if (isActionRegistered(ANDROID_SYNC_ACTION)) SyncMethod.ANDROID_STUDIO_ACTION else SyncMethod.GRADLE_REFRESH
    }
}
