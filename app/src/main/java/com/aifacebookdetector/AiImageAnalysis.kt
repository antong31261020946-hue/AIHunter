package com.aifacebookdetector

import kotlinx.serialization.Serializable

@Serializable
data class AiImageAnalysis(
    val is_ai: Boolean,
    val score: Int,
    val reason: String = ""
)
