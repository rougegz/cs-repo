package com.stremiouniversal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

private val codec: ObjectMapper by lazy {
    ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun LinkRef.toJsonString(): String = codec.writeValueAsString(this)

fun parseLinkRef(json: String): LinkRef? = runCatching {
    codec.readValue(json, LinkRef::class.java)
}.getOrNull()

fun stringList(node: JsonNode?): List<String> {
    if (node == null || node.isNull) return emptyList()
    if (node.isArray) return node.mapNotNull { it.asText(null)?.trim()?.takeIf { it.isNotEmpty() } }
    if (node.isValueNode) {
        val text = node.asText().trim()
        if (text.isEmpty()) return emptyList()
        return text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    return emptyList()
}

fun extractMetaEntry(body: String, id: String): CatalogEntry? {
    val wrapped = runCatching { codec.readValue(body, CatalogResponse::class.java) }.getOrNull()
    wrapped?.let { it.meta ?: it.metas?.firstOrNull { meta -> meta.id == id } ?: it.metas?.firstOrNull() }
        ?.takeIf { it.name.isNotEmpty() }?.let { return it }
    return runCatching { codec.readValue(body, CatalogEntry::class.java) }.getOrNull()
        ?.takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
}
