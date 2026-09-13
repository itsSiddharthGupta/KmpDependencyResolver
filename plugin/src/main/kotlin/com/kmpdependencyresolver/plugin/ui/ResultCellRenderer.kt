package com.kmpdependencyresolver.plugin.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

class ResultCellRenderer : ColoredListCellRenderer<SearchResultItem>() {
    override fun customizeCellRenderer(
        list: JList<out SearchResultItem>,
        value: SearchResultItem,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append("[${value.group.name.lowercase().replaceFirstChar(Char::uppercase)}] ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        append(value.candidate.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.candidate.coordinates.notation}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${value.evidenceLabel}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        value.freshnessLabel?.let { append(" · $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
    }
}
