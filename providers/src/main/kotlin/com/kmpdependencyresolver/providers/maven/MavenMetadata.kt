package com.kmpdependencyresolver.providers.maven

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Hardened XML parsing for repository indices and `maven-metadata.xml`. */
internal object MavenMetadata {
    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    fun parse(payload: ByteArray): Document = synchronized(factory) { factory.newDocumentBuilder() }
        .parse(ByteArrayInputStream(payload))

    /** Versions in repository order (oldest first). */
    fun versions(payload: ByteArray): List<String> = parse(payload).getElementsByTagName("version").let { nodes ->
        (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }.filter(String::isNotBlank)
}

internal fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
