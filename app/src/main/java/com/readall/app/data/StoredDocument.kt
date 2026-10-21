package com.readall.app.data

import kotlinx.serialization.Serializable

@Serializable
data class StoredDocument(
    val id: String,
    val title: String,
    val originalName: String,
    val pageCount: Int,
    val lastPage: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long
)
