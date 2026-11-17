package com.nekroz.meowsub.ui

import androidx.annotation.StringRes

/** A one-shot message for the snackbar; [arg] fills a single `%1$s` placeholder when present. */
data class UiMessage(@param:StringRes val res: Int, val arg: String? = null)
