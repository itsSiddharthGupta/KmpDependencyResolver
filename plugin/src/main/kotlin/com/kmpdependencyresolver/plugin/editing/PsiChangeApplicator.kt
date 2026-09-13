package com.kmpdependencyresolver.plugin.editing

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager

data class ApplyResult(val changedPaths: List<String>)

class ApplyRejectedException(val code: String, message: String) : IllegalStateException(message)

class PsiChangeApplicator(private val project: Project) {
    fun apply(preview: ChangePreview): ApplyResult {
        val resolved = preflight(preview)
        WriteCommandAction.writeCommandAction(project)
            .withName("Add KMP dependency")
            .withGlobalUndo()
            .run<RuntimeException> {
                val inside = preflight(preview)
                val originals = inside.associate { it.preview.path to it.document.text }
                try {
                    inside.forEach { it.document.setText(it.preview.proposedText) }
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                } catch (exception: RuntimeException) {
                    inside.forEach { item -> originals[item.preview.path]?.let(item.document::setText) }
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    throw exception
                }
            }
        return ApplyResult(resolved.map { it.preview.path })
    }

    private fun preflight(preview: ChangePreview): List<ResolvedChange> = preview.files.map { change ->
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(change.path)
            ?: throw ApplyRejectedException("FILE_NOT_FOUND", "File not found: ${change.path}")
        if (!file.isWritable) throw ApplyRejectedException("READ_ONLY", "File is read-only: ${change.path}")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: throw ApplyRejectedException("DOCUMENT_UNAVAILABLE", "No document for: ${change.path}")
        if (document.modificationStamp != change.modificationStamp ||
            ChangePreviewService.sha256(document.text) != change.originalSha256
        ) throw ApplyRejectedException("STALE_STATE", "File changed since preview: ${change.path}")
        try {
            ChangePreviewService.validate(project, change.kind, change.proposedText)
        } catch (exception: PreviewException) {
            throw ApplyRejectedException("INVALID_RENDER", exception.message ?: "Invalid rendered file")
        }
        ResolvedChange(change, document)
    }

    private data class ResolvedChange(val preview: FileChangePreview, val document: com.intellij.openapi.editor.Document)
}
