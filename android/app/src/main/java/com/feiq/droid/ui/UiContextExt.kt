package com.feiq.droid.ui

import android.content.Context
import androidx.core.content.ContextCompat

fun Context.getColorCompat(resId: Int): Int = ContextCompat.getColor(this, resId)
