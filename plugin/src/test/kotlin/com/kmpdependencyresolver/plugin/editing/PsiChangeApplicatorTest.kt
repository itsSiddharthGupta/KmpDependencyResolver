package com.kmpdependencyresolver.plugin.editing

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kmpdependencyresolver.core.change.CatalogOperation
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.core.change.GradleOperation
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.plugin.project.GradleModuleSnapshot
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class PsiChangeApplicatorTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var catalog: Path
    private lateinit var build: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("kmp-preview-")
        catalog = directory.resolve("gradle/libs.versions.toml")
        build = directory.resolve("shared/build.gradle.kts")
        Files.createDirectories(catalog.parent)
        Files.createDirectories(build.parent)
        Files.writeString(catalog, "[versions]\n\n[libraries]\n\n[plugins]\n")
        Files.writeString(build, "plugins {\n}\nkotlin { sourceSets { commonMain.dependencies {\n} } }\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
    }

    override fun tearDown() {
        try {
            directory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test one apply changes both files and one undo restores both`() {
        val preview = service().preview(plan(), snapshot())
        val originals = preview.files.associate { it.path to it.originalText }

        val result = PsiChangeApplicator(project).apply(preview)

        assertThat(result.changedPaths).containsExactlyInAnyOrder(catalog.toString(), build.toString())
        assertThat(document(catalog).text).contains("ktor-client-core")
        assertThat(document(build).text).contains("implementation(libs.ktor.client.core)")
        assertThat(UndoManager.getInstance(project).isUndoAvailable(null)).isTrue()
        UndoManager.getInstance(project).undo(null)
        assertThat(document(catalog).text).isEqualTo(originals[catalog.toString()])
        assertThat(document(build).text).isEqualTo(originals[build.toString()])
    }

    fun `test stale and read only files reject before writes`() {
        val preview = service().preview(plan(), snapshot())
        WriteCommandAction.runWriteCommandAction(project) { document(build).insertString(0, "// changed\n") }
        assertThatThrownBy { PsiChangeApplicator(project).apply(preview) }
            .isInstanceOf(ApplyRejectedException::class.java).extracting("code").isEqualTo("STALE_STATE")
        assertThat(document(catalog).text).isEqualTo(preview.files.single { it.path == catalog.toString() }.originalText)

        WriteCommandAction.runWriteCommandAction(project) {
            document(build).setText(preview.files.single { it.path == build.toString() }.originalText)
        }
        val fresh = service().preview(plan(), snapshot())
        val catalogFile = LocalFileSystem.getInstance().findFileByNioFile(catalog)!!
        WriteAction.run<RuntimeException> { catalogFile.isWritable = false }
        try {
            assertThatThrownBy { PsiChangeApplicator(project).apply(fresh) }
                .isInstanceOf(ApplyRejectedException::class.java).extracting("code").isEqualTo("READ_ONLY")
        } finally {
            WriteAction.run<RuntimeException> { catalogFile.isWritable = true }
        }
    }

    fun `test invalid proposed file leaves both originals unchanged`() {
        val preview = service().preview(plan(), snapshot())
        val invalid = preview.copy(files = preview.files.map {
            if (it.kind == PreviewFileKind.KOTLIN_DSL) it.copy(proposedText = "kotlin {") else it
        })

        assertThatThrownBy { PsiChangeApplicator(project).apply(invalid) }
            .isInstanceOf(ApplyRejectedException::class.java).extracting("code").isEqualTo("INVALID_RENDER")
        preview.files.forEach { assertThat(document(Path.of(it.path)).text).isEqualTo(it.originalText) }
    }

    private fun service() = ChangePreviewService(project)
    private fun plan() = ChangePlan(
        ":shared",
        listOf(
            CatalogOperation.PutVersion("ktor-client-core", "3.1.0"),
            CatalogOperation.PutLibrary("ktor-client-core", Coordinates("io.ktor", "ktor-client-core"), "ktor-client-core"),
        ),
        listOf(GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false)),
    )
    private fun snapshot() = ProjectSnapshot(
        directory.toString(), catalog.toString(),
        listOf(GradleModuleSnapshot(":shared", build.toString(), emptySet(), emptyList(), emptySet(), emptySet())),
        emptyMap(), emptySet(),
    )
    private fun document(path: Path) = FileDocumentManager.getInstance()
        .getDocument(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!)!!
}
