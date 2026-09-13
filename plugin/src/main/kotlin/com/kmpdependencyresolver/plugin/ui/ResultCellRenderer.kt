package com.kmpdependencyresolver.plugin.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kmpdependencyresolver.core.model.TargetFamily
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer

class ResultCellRenderer : ListCellRenderer<SearchResultItem> {
    override fun getListCellRendererComponent(
        list: JList<out SearchResultItem>,
        value: SearchResultItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val foreground = if (isSelected) list.selectionForeground else list.foreground
        val muted = if (isSelected) list.selectionForeground else JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val background = if (isSelected) list.selectionBackground else list.background
        val previousSection = if (index > 0) list.model.getElementAt(index - 1).section else null

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            this.background = background
            border = JBUI.Borders.empty(8, 10, 9, 10)

            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBLabel(value.candidate.coordinates.artifact).apply {
                    font = list.font.deriveFont(Font.BOLD)
                    this.foreground = foreground
                }, BorderLayout.CENTER)
                add(JBLabel(selectedVersion(value)).apply {
                    this.foreground = foreground
                }, BorderLayout.EAST)
            })
            add(JBLabel(value.candidate.coordinates.notation).apply {
                this.foreground = muted
                font = list.font.deriveFont(list.font.size2D - 1f)
            })
            add(JBLabel(metadata(value)).apply {
                this.foreground = muted
                font = list.font.deriveFont(list.font.size2D - 1f)
            })
            add(JTextArea(value.candidate.displayName).apply {
                isEditable = false
                isFocusable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                rows = 2
                this.foreground = foreground
                font = list.font
                border = JBUI.Borders.emptyTop(3)
            })
            toolTipText = value.publisherName?.let { "Official publisher: $it" }
            accessibleContext.accessibleName = "${value.candidate.coordinates.notation}, ${metadata(value)}"
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            this.background = background
            if (previousSection != value.section) {
                add(JBLabel(value.section.displayName).apply {
                    isOpaque = true
                    this.background = list.background
                    font = list.font.deriveFont(Font.BOLD)
                    this.foreground = list.foreground
                    border = JBUI.Borders.empty(10, 10, 4, 10)
                }, BorderLayout.NORTH)
            }
            add(content, BorderLayout.CENTER)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, list.foreground.let { color ->
                java.awt.Color(color.red, color.green, color.blue, 32)
            })
        }
    }

    private fun selectedVersion(value: SearchResultItem): String =
        value.candidate.versions.firstOrNull { it.stable }?.value
            ?: value.candidate.versions.firstOrNull()?.value.orEmpty()

    private fun metadata(value: SearchResultItem): String = buildList {
        add(value.group.displayName())
        add(value.evidenceLabel)
        if (value.candidate.supportedTargets.isNotEmpty()) {
            add(value.candidate.supportedTargets.map { it.userFacingName() }.sortedBy(String::lowercase).joinToString())
        }
        if (value.candidate.provenance.isNotEmpty()) {
            add(value.candidate.provenance.map { it.providerId }.distinct().sorted().joinToString())
        }
        value.freshnessLabel?.let(::add)
    }.joinToString(" · ")

    private fun ResultGroup.displayName() = name.lowercase().replaceFirstChar(Char::uppercase)

    private fun TargetFamily.userFacingName() = when (this) {
        TargetFamily.JVM -> "JVM"
        TargetFamily.IOS -> "iOS"
        TargetFamily.MACOS -> "macOS"
        TargetFamily.MINGW -> "Windows"
        TargetFamily.JS -> "JS"
        TargetFamily.WASM -> "Wasm"
        else -> name.lowercase().replaceFirstChar(Char::uppercase)
    }
}
