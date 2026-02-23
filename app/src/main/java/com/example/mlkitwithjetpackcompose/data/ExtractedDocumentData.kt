package com.example.mlkitwithjetpackcompose.data

data class ExtractedDocumentData(
    var documentType: IdType,
    val name: String?= null,
    val dob: String?= null,
    val address: String?= null,
    val imageUri: String?,

)
