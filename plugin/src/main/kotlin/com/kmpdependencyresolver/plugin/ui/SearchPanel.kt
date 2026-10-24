package com.kmpdependencyresolver.plugin.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.kmpdependencyresolver.core.compatibility.SourceSetRecommender
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.ModuleModel
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.defaultVersion
import com.kmpdependencyresolver.plugin.DependencyResolverService
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import com.kmpdependencyresolver.plugin.settings.ResolverSettings
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.util.concurrent.Executors
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SearchPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.service<DependencyResolverService>()
    private val query = SearchTextField(false)
    private val module = JComboBox<String>()
    private val target = JComboBox(arrayOf("Any target", "Android", "JVM", "iOS", "Desktop", "JS", "Wasm"))
    private val previews = JCheckBox("Include pre-release")
    private val status = JBLabel("Search Kotlin Multiplatform dependencies")
    private val listModel = DefaultListModel<SearchResultItem>()
    private val results = JBList(listModel)
    private val scheduled = Executors.newSingleThreadScheduledExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val presenter: SearchPresenter
    @Volatile private var snapshot: ProjectSnapshot? = null
    private var updatingModules = false
    private val addButton = JButton("Add…")

    init {
        border = JBUI.Borders.empty(8)
        query.textEditor.accessibleContext.accessibleName = "Dependency search"
        module.accessibleContext.accessibleName = "Gradle module"
        target.accessibleContext.accessibleName = "Required target"
        results.accessibleContext.accessibleName = "Dependency results"
        results.cellRenderer = ResultCellRenderer()
        // Modules Add can't edit (plain JVM/Android, Groovy, …) stay selectable for Copy but say so up front.
        module.renderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.orEmpty())
                if (value != null && snapshot?.addUnavailableReason(value) != null) append("  (Copy only)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        previews.isSelected = ApplicationManager.getApplication().service<ResolverSettings>().state.includePreReleases

        val debounceScheduler = TaskScheduler { delay, task ->
            val future = scheduled.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(true) }
        }
        val workerScheduler = TaskScheduler { _, task ->
            val future = workers.submit(task)
            Cancellable { future.cancel(true) }
        }
        presenter = SearchPresenter(
            DependencySearchGateway(service::search), debounceScheduler, workerScheduler,
            UiDispatcher { task -> ApplicationManager.getApplication().invokeLater(task) },
            // Never read the project model on the EDT here; the combo is filled by refreshModules().
            activeModule = { module.selectedItem as? String }, onState = ::render,
            onAdd = ::addDependency,
        )
        module.addActionListener {
            updateAddAvailability()
            if (!updatingModules) search()
        }

        val filters = JPanel().apply { add(module); add(target); add(previews) }
        val north = JPanel(BorderLayout()).apply { add(query, BorderLayout.NORTH); add(filters, BorderLayout.CENTER); add(status, BorderLayout.SOUTH) }
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(results), BorderLayout.CENTER)
        add(JPanel().apply {
            add(JButton("Copy").apply { addActionListener { showCopyMenu() } })
            add(addButton.apply { addActionListener { addSelectedDependency() } })
        }, BorderLayout.SOUTH)

        query.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = search()
            override fun removeUpdate(e: DocumentEvent?) = search()
            override fun changedUpdate(e: DocumentEvent?) = search()
        })
        target.addActionListener { search() }
        previews.addActionListener { search() }
        installKeyboardActions()

        val connection = project.messageBus.connect(this)
        connection.subscribe(ProjectDataImportListener.TOPIC, object : ProjectDataImportListener {
            override fun onImportFinished(projectPath: String?) = refreshModules()
        })
        connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow.id == TOOL_WINDOW_ID) refreshModules()
            }
        })
        refreshModules()
    }

    /** Reads the project model in a cancellable background read action and refills the module list on the EDT. */
    private fun refreshModules() {
        ReadAction.nonBlocking<ProjectSnapshot?> { runCatching(service::projectSnapshot).onFailure { LOG.warn("Project model unavailable", it) }.getOrNull() }
            .expireWith(this)
            .finishOnUiThread(ModalityState.stateForComponent(this)) { loaded ->
                snapshot = loaded
                val previous = module.selectedItem as? String
                updatingModules = true
                try {
                    module.removeAllItems()
                    loaded?.modules?.forEach { module.addItem(it.id) }
                    val preferred = previous?.takeIf { id -> loaded?.modules?.any { it.id == id } == true }
                        ?: loaded?.let(service::activeModuleId)
                    module.selectedItem = preferred
                } finally {
                    updatingModules = false
                }
                updateAddAvailability()
                if ((module.selectedItem as? String) != previous) search()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun updateAddAvailability() {
        val reason = (module.selectedItem as? String)?.let { id -> snapshot?.addUnavailableReason(id) }
        addButton.isEnabled = reason == null
        addButton.toolTipText = reason
    }

    private fun search() {
        presenter.onQueryChanged(query.text, selectedTargets(), previews.isSelected, module.selectedItem as? String)
    }

    private fun selectedTargets(): Set<TargetFamily> = when (target.selectedItem) {
        "Android" -> setOf(TargetFamily.ANDROID)
        "JVM" -> setOf(TargetFamily.JVM)
        "iOS" -> setOf(TargetFamily.IOS)
        "Desktop" -> setOf(TargetFamily.JVM, TargetFamily.MACOS, TargetFamily.LINUX, TargetFamily.MINGW)
        "JS" -> setOf(TargetFamily.JS)
        "Wasm" -> setOf(TargetFamily.WASM)
        else -> emptySet()
    }

    private fun render(state: SearchUiState) {
        listModel.clear()
        state.results.forEach(listModel::addElement)
        val messages = state.providerMessages + listOfNotNull(service.recipeStatus()?.let(::recipeStatusText))
        status.text = when {
            state.searching -> "Searching…"
            messages.isNotEmpty() -> "${state.results.size} results · ${messages.joinToString()}"
            else -> "${state.results.size} results"
        }
    }

    private fun installKeyboardActions() {
        query.textEditor.inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "cancelSearch")
        query.textEditor.actionMap.put("cancelSearch", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) { presenter.cancel(); status.text = "Search cancelled" }
        })
        results.inputMap.put(KeyStroke.getKeyStroke('C', Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyDependency")
        results.actionMap.put("copyDependency", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = showCopyMenu()
        })
        results.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "confirmDependency")
        results.actionMap.put("confirmDependency", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = addSelectedDependency()
        })
    }

    private fun addSelectedDependency() {
        val item = results.selectedValue ?: return
        addDependency(item.candidate, module.selectedItem as? String, item.defaultVersion)
    }

    private fun addDependency(candidate: Candidate, moduleId: String?) =
        addDependency(candidate, moduleId, defaultVersion(candidate, previews.isSelected))

    private fun addDependency(candidate: Candidate, moduleId: String?, preferredVersion: String?) {
        if (moduleId == null) {
            Messages.showErrorDialog(project, "No Gradle module is available for Add. Copy is still available.", "Project not supported")
            return
        }
        val flow = AddDependencyFlow(service)
        var confirmation = when (val started = inBackground("Reading project model") { flow.start(candidate, moduleId, preferredVersion) }) {
            null -> return
            else -> started.getOrElse { exception ->
                showError((exception as? AddUnavailableException)?.error ?: UserFacingError.from("UNSUPPORTED_PROJECT", exception))
                return
            }
        }
        while (true) {
            val dialog = AddDependencyDialog(project, confirmation)
            if (!dialog.showAndGet()) return
            val options = dialog.options()
            val preview = inBackground("Preparing preview") { flow.preview(options, confirmation) }?.getOrElse { exception ->
                showError(UserFacingError.from("UNEXPECTED", exception))
                return
            } ?: return
            if (!preview.applyEnabled) {
                showError(preview.error ?: UserFacingError.from("UNEXPECTED"))
                // Reopen with the user's choices rather than the original defaults.
                confirmation = confirmation.withOptions(options)
                continue
            }
            if (!ChangePreviewDialog(project, preview).showAndGet()) return
            when (val outcome = flow.apply(preview)) {
                is AddApplyOutcome.Success -> {
                    showSuccess(outcome)
                    refreshModules()
                    return
                }
                is AddApplyOutcome.Stale -> confirmation = outcome.confirmation
                is AddApplyOutcome.Failure -> {
                    showError(outcome.error)
                    return
                }
            }
        }
    }

    /**
     * Runs [task] on a pooled thread under a cancellable modal progress, so project-model reads and preview
     * rendering stay off the EDT. Returns null when the user cancels.
     */
    private fun <T> inBackground(title: String, task: () -> T): Result<T>? {
        var result: Result<T>? = null
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = try {
                    Result.success(task())
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: RuntimeException) {
                    Result.failure(exception)
                }
            },
            title, true, project,
        )
        return if (completed) result else null
    }

    private fun showSuccess(outcome: AddApplyOutcome.Success) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("KMP Dependency Resolver")
            .createNotification(
                "Dependency added",
                "Changed ${outcome.relativePaths.joinToString()}",
                NotificationType.INFORMATION,
            )
        if (outcome.suggestGradleSync) {
            notification.addAction(NotificationAction.createSimpleExpiring("Sync Gradle project") {
                if (!GradleSyncTrigger(project).sync()) {
                    Messages.showWarningDialog(
                        project,
                        "Gradle sync could not be started automatically. Use File | Sync Project with Gradle Files.",
                        "Sync Gradle project",
                    )
                }
            })
        }
        notification.notify(project)
    }

    private fun showError(error: UserFacingError) {
        LOG.warn("${error.code}: dependency Add stopped", error.cause)
        Messages.showErrorDialog(project, "${error.message}\n\nDiagnostic: ${error.code}", error.title)
    }

    private fun showCopyMenu() {
        val item = results.selectedValue ?: return
        val selection = copySelection(item) ?: return
        JPopupMenu().apply {
            CopyKind.entries.forEach { kind ->
                add(JMenuItem(kind.label).apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(CopyFormatter().format(selection, kind)))
                    }
                })
            }
            show(results, 8, 8)
        }
    }

    /** Copy uses the same version, source set, configuration and recipe that Add would preselect. */
    private fun copySelection(item: SearchResultItem): CopySelection? {
        val version = item.defaultVersion ?: return null
        val moduleSnapshot = snapshot?.modules?.firstOrNull { it.id == module.selectedItem }
        val sourceSet = moduleSnapshot
            ?.let { SourceSetRecommender().recommend(item.candidate, ModuleModel(it.id, it.sourceSets)).sourceSetName }
            ?: "commonMain"
        val recipe = service.recipes(item.candidate).firstOrNull()
        return CopySelection(
            item.candidate, version, sourceSet, recipe?.preferredConfiguration ?: "implementation", recipe,
            kspTargets = moduleSnapshot?.sourceSetTargetNames?.get(sourceSet).orEmpty(),
        )
    }

    override fun dispose() {
        presenter.cancel()
        scheduled.shutdownNow()
        workers.shutdownNow()
    }

    private companion object {
        val LOG = Logger.getInstance(SearchPanel::class.java)
        const val TOOL_WINDOW_ID = "KMP Dependencies"
    }
}
