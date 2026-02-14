package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.monkopedia.healthdisconnect.R

@Composable
fun LoadingScreen() {
    Box {
        Text(stringResource(R.string.loading_message))
    }
}
