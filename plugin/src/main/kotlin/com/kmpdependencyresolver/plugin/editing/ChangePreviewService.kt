package com.kmpdependencyresolver.plugin.editing

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import java.security.MessageDigest
import org.jetbrains.kotlin.psi.KtPsiFactory

enum class PreviewFileKind { VERSION_CATALOG, KOTLIN_DSL }

data class FileChangePreview(
    val path: String,
    val kind: PreviewFileKind,
    val originalText: String,
    val proposedText: String,
    val originalSha256: String,
    val modificationStamp: Long,
)

data class ChangePreview(val files: List<FileChangePreview>, val unifiedDiff: String)

class PreviewException(val code: String, message: String) : IllegalStateException(message)

class ChangePreviewService(
    private val project: Project,
    private val catalogRenderer: CatalogPlanRenderer = CatalogPlanRenderer(),
    private val kotlinRenderer: KotlinDslPlanRenderer = KotlinDslPlanRenderer(),
) {
    fun preview(plan: ChangePlan, snapshot: ProjectSnapshot): ChangePreview {
        val module = snapshot.modules.firstOrNull { it.id == plan.moduleId }
            ?: throw PreviewException("MODULE_NOT_FOUND", "Module ${plan.moduleId} no longer exists.")
        val catalogPath = snapshot.catalogPath
            ?: throw PreviewException("CATALOG_NOT_FOUND", "gradle/libs.versions.toml is required.")
        val catalog = filePreview(catalogPath, PreviewFileKind.VERSION_CATALOG) {
            catalogRenderer.render(it, plan.catalogOperations)
        }
        val build = filePreview(module.buildFile, PreviewFileKind.KOTLIN_DSL) {
            kotlinRenderer.render(it, plan.gradleOperations)
        }
        val changed = listOf(catalog, build).filter { it.originalText != it.proposedText }
        changed.forEach { validate(project, it.kind, it.proposedText) }
        return ChangePreview(changed, changed.joinToString("\n") { unifiedDiff(it) })
    }

    private fun filePreview(path: String, kind: PreviewFileKind, render: (String) -> String): FileChangePreview {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?: throw PreviewException("FILE_NOT_FOUND", "File not found: $path")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: throw PreviewException("DOCUMENT_UNAVAILABLE", "No document for: $path")
        val original = document.text
        return FileChangePreview(path, kind, original, render(original), sha256(original), document.modificationStamp)
    }

    companion object {
        fun validate(project: Project, kind: PreviewFileKind, text: String) {
            val psi = when (kind) {
                PreviewFileKind.KOTLIN_DSL -> KtPsiFactory(project).createFile("build.gradle.kts", text)
                PreviewFileKind.VERSION_CATALOG -> PsiFileFactory.getInstance(project).createFileFromText("libs.versions.toml", text)
            }
            val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
            if (error != null) throw PreviewException("INVALID_RENDER", error.errorDescription)
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

        private fun unifiedDiff(change: FileChangePreview): String = buildString {
            appendLine("--- ${change.path}")
            appendLine("+++ ${change.path}")
            appendLine("@@ -1,${change.originalText.lines().size} +1,${change.proposedText.lines().size} @@")
            change.originalText.lineSequence().forEach { appendLine("-$it") }
            change.proposedText.lineSequence().forEach { appendLine("+$it") }
        }.trimEnd()
    }
}
