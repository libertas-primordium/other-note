package com.libertasprimordium.othernote.data

interface NoteListPreferenceStore {
    suspend fun loadSortId(): String?
    suspend fun saveSortId(sortId: String)
}

object NoopNoteListPreferenceStore : NoteListPreferenceStore {
    override suspend fun loadSortId(): String? = null
    override suspend fun saveSortId(sortId: String) = Unit
}
