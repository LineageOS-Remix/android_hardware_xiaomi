/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.geq.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.settingsBackground
import com.android.settingslib.spa.framework.theme.SettingsDimension

@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(SettingsDimension.itemPadding)
            .then(modifier),
        color = MaterialTheme.colorScheme.settingsBackground
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    PresetSelector(viewModel = viewModel)
                }
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBands(viewModel = viewModel)
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxHeight()
            ) {
                PresetSelector(viewModel = viewModel)
                EqualizerBands(viewModel = viewModel)
            }
        }
    }
}
