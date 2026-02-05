package com.example.mlkitwithjetpackcompose.data

data class ExtractedDocument(
    val type: IdType,
    val idNumber: String,
    val name: String?,      // Nullable, as it's hard to capture 100% correctly
    val dob: String?,        // Nullable
    val address: String? = null,    // Add this
    val isExpired: Boolean = false,
    val gender: Gender? = null
)