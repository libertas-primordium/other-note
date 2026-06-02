package com.libertasprimordium.othernote.ui

fun noteGridColumnCount(availableWidthDp: Int): Int =
    when {
        availableWidthDp < 320 -> 1
        availableWidthDp < 720 -> 2
        else -> (availableWidthDp / 280).coerceIn(2, 6)
    }
