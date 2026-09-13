package com.kmpdependencyresolver.plugin.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.kmpdependencyresolver.plugin.editing.ChangePreview

class DiffRequestFactory(private val project: Project) {
    fun create(preview: ChangePreview): List<SimpleDiffRequest> = preview.files.map { file ->
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
        val contents = DiffContentFactory.getInstance()
        SimpleDiffRequest(
            file.path,
            contents.create(project, file.originalText, fileType),
            contents.create(project, file.proposedText, fileType),
            "Original",
            "Proposed",
        )
    }
}
