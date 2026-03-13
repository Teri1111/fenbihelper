package com.example.myapplication.data

import android.net.Uri
import java.io.File

data class CaptureResult(
    val uri: Uri,
    val file: File,
    val createdAtMillis: Long,
)

sealed interface CaptureOutcome {
    data class Success(val result: CaptureResult) : CaptureOutcome

    data class Failure(
        val message: String,
        val requiresReauthorization: Boolean = false,
    ) : CaptureOutcome
}

