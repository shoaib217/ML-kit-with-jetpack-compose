package com.example.mlkitwithjetpackcompose.data

data class ExtractedDocumentData(
    var documentType: IdType,
    val name: String?,
    val dob: String?,
    val address: String?,
    val imageUri: String?,

)
