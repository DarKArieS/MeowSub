package com.nekroz.meowsub.ui

import androidx.annotation.StringRes

/** A one-shot message for the snackbar; [args] fill the string's `%1$s`, `%2$s`… placeholders. */
class UiMessage(@param:StringRes val res: Int, vararg val args: String)
