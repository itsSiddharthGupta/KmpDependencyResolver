package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.TargetFamily
import java.awt.Component
import java.awt.Container
import javax.swing.JList
import javax.swing.JTextArea
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultCellRendererTest {
    @Test
    fun `row prioritizes identity metadata and a wrapped description`() {
        val candidate = Candidate(
            Coordinates("io.ktor", "ktor-client-content-negotiation"),
            "JSON serialization and content negotiation for Ktor server and client pipelines",
            listOf(DependencyVersion("3.1.0", true)),
            setOf(TargetFamily.JVM, TargetFamily.IOS),
            EvidenceKind.VERIFIED,
            setOf(Provenance("maven-central"), Provenance("klibs")),
        )
        val item = SearchResultItem(
            candidate,
            ResultGroup.RECOMMENDED,
            "Verified",
            null,
            PublisherSection.OFFICIAL,
            "Ktor",
        )
        val list = JList(arrayOf(item))

        val rendered = ResultCellRenderer().getListCellRendererComponent(list, item, 0, false, false)
        val text = descendants(rendered).mapNotNull { componentText(it) }
        val description = descendants(rendered).filterIsInstance<JTextArea>().single()

        assertThat(text).contains(
            "Official libraries",
            "ktor-client-content-negotiation",
            "3.1.0",
            "io.ktor:ktor-client-content-negotiation",
            "Recommended · Verified · iOS, JVM · klibs, maven-central",
            candidate.displayName,
        )
        assertThat(description.lineWrap).isTrue()
        assertThat(description.wrapStyleWord).isTrue()
        assertThat(description.rows).isEqualTo(2)
    }

    private fun descendants(component: Component): List<Component> = buildList {
        add(component)
        if (component is Container) component.components.forEach { addAll(descendants(it)) }
    }

    private fun componentText(component: Component): String? = when (component) {
        is javax.swing.JLabel -> component.text
        is JTextArea -> component.text
        else -> null
    }
}
