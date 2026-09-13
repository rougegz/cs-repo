package com.stremiouniversal

import com.fasterxml.jackson.databind.DeserializationFeature
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
