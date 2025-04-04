package com.github.capntrips.kernelflasher.ui.screens.main

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.R
import com.github.capntrips.kernelflasher.ui.components.DataCard
import com.github.capntrips.kernelflasher.ui.components.DataRow
import com.github.capntrips.kernelflasher.ui.components.SlotCard
import kotlinx.serialization.ExperimentalSerializationApi

private fun parseSusfsVersion(version: SusStatus, context: Context): String {
  return context.getString(R.string.susfs_version, version.version, version.mode)
}

@Composable
fun MyOutlinedButton(onclick: () -> Unit, content: @Composable () -> Unit) {
  OutlinedButton(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 6.dp, vertical = 2.dp),
    shape = RoundedCornerShape(10.dp),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.primary
    ),
    border = BorderStroke(
      width = 1.5.dp,
      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    ),
    onClick = onclick
  ) { content() }
}

@ExperimentalMaterial3Api
@ExperimentalSerializationApi
@Composable
fun ColumnScope.MainContent(
  viewModel: MainViewModel,
  navController: NavController
) {
  val context = LocalContext.current
  DataCard(title = stringResource(R.string.device)) {
    val cardWidth = remember { mutableIntStateOf(0) }
    DataRow(
      stringResource(R.string.model),
      "${Build.MODEL} (${Build.DEVICE})",
      mutableMaxWidth = cardWidth
    )
    DataRow(stringResource(R.string.build_number), Build.ID, mutableMaxWidth = cardWidth)
    if (viewModel.susfsVersion.isSupported()) {
      DataRow(
        "SusFS",
        if (viewModel.susfsVersion.isSupported()) parseSusfsVersion(
          viewModel.susfsVersion,
          context
        ) else context.getString(R.string.unsupported),
        mutableMaxWidth = cardWidth,
        clickable = true
      )
    }
    DataRow(
      stringResource(R.string.kernel_version),
      viewModel.kernelVersion,
      mutableMaxWidth = cardWidth,
      clickable = true
    )
    if (viewModel.isAb) {
      DataRow(
        stringResource(R.string.slot_suffix),
        viewModel.slotSuffix,
        mutableMaxWidth = cardWidth
      )
    }
  }
  Spacer(Modifier.height(16.dp))
  SlotCard(
    title = stringResource(if (viewModel.isAb) R.string.slot_a else R.string.slot),
    viewModel = viewModel.slotA,
    navController = navController
  )
  if (viewModel.isAb) {
    Spacer(Modifier.height(16.dp))
    SlotCard(
      title = stringResource(R.string.slot_b),
      viewModel = viewModel.slotB!!,
      navController = navController
    )
  }
  Spacer(Modifier.height(16.dp))
  AnimatedVisibility(!viewModel.isRefreshing) {
    MyOutlinedButton(onclick = { navController.navigate("backups") }) { Text(stringResource(R.string.backups)) }
  }
  if (viewModel.hasRamoops) {
    MyOutlinedButton(onclick = { navController.navigate("ramoops") }) { Text(stringResource(R.string.save_ramoops)) }
  }
  AnimatedVisibility(!viewModel.isRefreshing) {
    MyOutlinedButton(onclick = { viewModel.saveDmesg(context) }) { Text(stringResource(R.string.save_dmesg)) }
  }
  AnimatedVisibility(!viewModel.isRefreshing) {
    MyOutlinedButton(onclick = { viewModel.saveLogcat(context) }) { Text(stringResource(R.string.save_logcat)) }
  }
  AnimatedVisibility(!viewModel.isRefreshing) {
    MyOutlinedButton(onclick = { navController.navigate("reboot") }) { Text(stringResource(R.string.reboot)) }
  }
}
