package com.hsp1020.elevate

/** A missing provider policy means no per-catalog restriction. */
internal object ElevateWatchProgressRules {
    fun flag(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
