package com.example.mlkitwithjetpackcompose.data

sealed class ExtractedDocument(val type: IdType) {
    data class Pan(val id: String, val name: String?, val dob: String?,val imageUri: String? = null ) : ExtractedDocument(IdType.PAN)

    data class Aadhaar(
        val id: String,
        val name: String?,
        val dob: String?,
        val gender: Gender?,
        val address: String? = null ,// Added Address
        val frontImageUri: String? = null,
        val backImageUri: String? = null
    ) : ExtractedDocument(IdType.AADHAAR)

    data class DrivingLicense(
        val id: String, val name: String?, val dob: String?,
        val address: String?, val isExpired: Boolean,
        val imageUri: String? = null
    ) : ExtractedDocument(IdType.DRIVING_LICENSE)

    data class Passport(
        val id: String, val name: String?, val dob: String?,
        val gender: Gender?, val isExpired: Boolean,
        val address: String? = null, // Added Address
        val fatherName: String? = null,
        val motherName: String? = null,
        val spouseName: String? = null,
        val frontImageUri: String? = null,
        val backImageUri: String? = null
    ) : ExtractedDocument(IdType.PASSPORT)

    data class Selfie(val imageUri: String? = null) : ExtractedDocument(IdType.SELFIE)
}