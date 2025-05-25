package com.github.capntrips.kernelflasher

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.ExperimentalUnitApi
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.capntrips.kernelflasher.ui.screens.RefreshableScreen
import com.github.capntrips.kernelflasher.ui.screens.backups.BackupsContent
import com.github.capntrips.kernelflasher.ui.screens.backups.SlotBackupsContent
import com.github.capntrips.kernelflasher.ui.screens.error.ErrorScreen
import com.github.capntrips.kernelflasher.ui.screens.main.MainContent
import com.github.capntrips.kernelflasher.ui.screens.main.MainViewModel
import com.github.capntrips.kernelflasher.ui.screens.reboot.RebootContent
import com.github.capntrips.kernelflasher.ui.screens.slot.SlotContent
import com.github.capntrips.kernelflasher.ui.screens.slot.SlotFlashContent
import com.github.capntrips.kernelflasher.ui.screens.updates.UpdatesAddContent
import com.github.capntrips.kernelflasher.ui.screens.updates.UpdatesChangelogContent
import com.github.capntrips.kernelflasher.ui.screens.updates.UpdatesContent
import com.github.capntrips.kernelflasher.ui.screens.updates.UpdatesViewContent
import com.github.capntrips.kernelflasher.ui.theme.KernelFlasherTheme
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import kotlin.system.exitProcess

@ExperimentalAnimationApi
@ExperimentalMaterialApi
@ExperimentalMaterial3Api
@ExperimentalSerializationApi
@ExperimentalUnitApi
class MainActivity : ComponentActivity() {
  companion object {
    const val TAG: String = "MainActivity"

    init {
      Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
    }
  }

  private var rootServiceConnected: Boolean = false
  private var viewModel: MainViewModel? = null
  private lateinit var mainListener: MainListener
  var isAwaitingResult = false

  inner class AidlConnection : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      if (!rootServiceConnected) {
        val ipc: IFilesystemService = IFilesystemService.Stub.asInterface(service)
        val binder: IBinder = ipc.fileSystemService
        onAidlConnected(FileSystemManager.getRemote(binder))
        rootServiceConnected = true
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      setContent {
        KernelFlasherTheme {
          ErrorScreen(stringResource(R.string.root_service_disconnected))
        }
      }
    }
  }

  suspend fun copyAsset(filename: String, filesDir: File, assets: AssetManager) =
    withContext(Dispatchers.IO) {
      val dest = File(filesDir, filename)
      try {
        assets.open(filename).use { inputStream ->
          dest.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
          }
        }
        Shell.cmd("chmod +x $dest").exec()
      } catch (e: Exception) {
        Log.e("AssetCopy", "Failed to copy asset: $filename", e)
      }
    }

  suspend fun copyNativeBinary(filename: String, filesDir: File, nativeLibDir: File) =
    withContext(Dispatchers.IO) {
      val binary = File(nativeLibDir, "lib$filename.so")
      val dest = File(filesDir, filename)
      if (dest.exists() && dest.length() == binary.length()) return@withContext
      try {
        binary.inputStream().use { inputStream ->
          dest.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
          }
        }
        Shell.cmd("chmod +x $dest").exec()
      } catch (e: Exception) {
        Log.e("BinaryCopy", "Failed to copy binary: $filename", e)
      }
    }

  suspend fun copyAssetsAndBinaries(
    context: Context
  ) = withContext(Dispatchers.IO) {
    val filesDir = context.filesDir
    val assets = context.assets
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

    listOf(
      async { copyNativeBinary("lptools_static", filesDir, nativeLibDir) },
      async { copyNativeBinary("httools_static", filesDir, nativeLibDir) },
      async { copyNativeBinary("magiskboot", filesDir, nativeLibDir) },
      async { copyAsset("mkbootfs", filesDir, assets) },
      async { copyAsset("ksuinit", filesDir, assets) },
      async { copyAsset("flash_ak3.sh", filesDir, assets) },
      async { copyAsset("flash_ak3_mkbootfs.sh", filesDir, assets) }
    ).joinAll()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    splashScreen.setOnExitAnimationListener { splashScreenView ->
      val scale = ObjectAnimator.ofPropertyValuesHolder(
        splashScreenView.view,
        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.9f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.9f)
      )
      scale.interpolator = AccelerateDecelerateInterpolator()
      scale.duration = 300L
      scale.doOnEnd { splashScreenView.remove() }
      scale.start()
    }

    val content: View = findViewById(android.R.id.content)
    content.viewTreeObserver.addOnPreDrawListener(
      object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
          return if (viewModel?.isRefreshing == false || Shell.isAppGrantedRoot() == false) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
            true
          } else {
            false
          }
        }
      }
    )

    Shell.getShell()
    if (Shell.isAppGrantedRoot()!!) {
      val intent = Intent(this, FilesystemService::class.java)
      RootService.bind(intent, AidlConnection())
    } else {
      setContent {
        KernelFlasherTheme {
          ErrorScreen(stringResource(R.string.root_required))
        }
      }
    }
  }

  fun onAidlConnected(fileSystemManager: FileSystemManager) {
    try {
      Shell.cmd("cd $filesDir").exec()
      lifecycleScope.launch {
        copyAssetsAndBinaries(applicationContext)
      }
    } catch (e: Exception) {
      Log.e(TAG, e.message, e)
      setContent {
        KernelFlasherTheme {
          ErrorScreen(e.message!!)
        }
      }
    }
    setContent {
      val navController = rememberNavController()
      viewModel = viewModel {
        val application =
          checkNotNull(get(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY))
        MainViewModel(application, fileSystemManager, navController)
      }
      val mainViewModel = viewModel!!

      val context = LocalContext.current

      var showExitDialog by remember { mutableStateOf(false) }

      KernelFlasherTheme {
        if (!mainViewModel.hasError) {
          mainListener = MainListener {
            mainViewModel.refresh(this)
          }
          val slotViewModelA = mainViewModel.slotA
          val slotViewModelB = mainViewModel.slotB
          val backupsViewModel = mainViewModel.backups
          val updatesViewModel = mainViewModel.updates
          val rebootViewModel = mainViewModel.reboot
          BackHandler(enabled = !mainViewModel.isRefreshing, onBack = {})
          // New back handler for exit
          BackHandler(enabled = true) {
            showExitDialog = true
          }
          val slotContentA: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_a"
              val slotViewModel = slotViewModelA
              if (slotViewModel.wasFlashSuccess.value != null && listOf(
                  "slot{slotSuffix}",
                  "slot"
                ).any { navController.currentDestination!!.route.equals(it) }
              ) {
                slotViewModel.clearFlash(this@MainActivity)
              }
              RefreshableScreen(mainViewModel, navController, swipeEnabled = true) {
                SlotContent(slotViewModel, slotSuffix, navController)
              }

            }
          val slotContentB: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_b"
              val slotViewModel = slotViewModelB
              if (slotViewModel!!.wasFlashSuccess.value != null && listOf(
                  "slot{slotSuffix}",
                  "slot"
                ).any { navController.currentDestination!!.route.equals(it) }
              ) {
                slotViewModel.clearFlash(this@MainActivity)
              }
              RefreshableScreen(mainViewModel, navController, swipeEnabled = true) {
                SlotContent(slotViewModel, slotSuffix, navController)
              }

            }
          val slotContent: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = ""
              val slotViewModel = slotViewModelA
              if (slotViewModel.wasFlashSuccess.value != null && listOf(
                  "slot{slotSuffix}",
                  "slot"
                ).any { navController.currentDestination!!.route.equals(it) }
              ) {
                slotViewModel.clearFlash(this@MainActivity)
              }
              RefreshableScreen(mainViewModel, navController, swipeEnabled = true) {
                SlotContent(slotViewModel, slotSuffix, navController)
              }

            }
          val slotFlashContentA: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_a"
              val slotViewModel = slotViewModelA
              RefreshableScreen(mainViewModel, navController) {
                SlotFlashContent(slotViewModel, slotSuffix, navController)
              }
            }
          val slotFlashContentB: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_b"
              val slotViewModel = slotViewModelB
              RefreshableScreen(mainViewModel, navController) {
                SlotFlashContent(slotViewModel!!, slotSuffix, navController)
              }
            }
          val slotFlashContent: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = ""
              val slotViewModel = slotViewModelA
              RefreshableScreen(mainViewModel, navController) {
                SlotFlashContent(slotViewModel, slotSuffix, navController)
              }
            }
          val slotBackupsContentA: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_a"
              val slotViewModel = slotViewModelA
              if (backStackEntry.arguments?.getString("backupId") != null) {
                backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              } else {
                backupsViewModel.clearCurrent()
              }
              RefreshableScreen(mainViewModel, navController) {
                SlotBackupsContent(slotViewModel, backupsViewModel, slotSuffix, navController)
              }
            }
          val slotBackupsContentB: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_b"
              val slotViewModel = slotViewModelB
              if (backStackEntry.arguments?.getString("backupId") != null) {
                backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              } else {
                backupsViewModel.clearCurrent()
              }
              RefreshableScreen(mainViewModel, navController) {
                SlotBackupsContent(slotViewModel!!, backupsViewModel, slotSuffix, navController)
              }
            }
          val slotBackupsContent: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = ""
              val slotViewModel = slotViewModelA
              if (backStackEntry.arguments?.getString("backupId") != null) {
                backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              } else {
                backupsViewModel.clearCurrent()
              }
              RefreshableScreen(mainViewModel, navController) {
                SlotBackupsContent(slotViewModel, backupsViewModel, slotSuffix, navController)
              }
            }
          val slotBackupFlashContentA: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_a"
              val slotViewModel = slotViewModelA
              backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              if (backupsViewModel.backups.containsKey(backupsViewModel.currentBackup)) {
                RefreshableScreen(mainViewModel, navController) {
                  SlotFlashContent(slotViewModel, slotSuffix, navController)
                }
              }

            }
          val slotBackupFlashContentB: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = "_b"
              val slotViewModel = slotViewModelB
              backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              if (backupsViewModel.backups.containsKey(backupsViewModel.currentBackup)) {
                RefreshableScreen(mainViewModel, navController) {
                  SlotFlashContent(slotViewModel!!, slotSuffix, navController)
                }
              }

            }
          val slotBackupFlashContent: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit =
            { backStackEntry ->
              val slotSuffix = ""
              val slotViewModel = slotViewModelA
              backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              if (backupsViewModel.backups.containsKey(backupsViewModel.currentBackup)) {
                RefreshableScreen(mainViewModel, navController) {
                  SlotFlashContent(slotViewModel, slotSuffix, navController)
                }
              }
            }
          NavHost(
            navController = navController, startDestination = "main",
            enterTransition = {
              fadeIn(animationSpec = tween(durationMillis = 400, easing = EaseInOut))
            },
            exitTransition = {
              fadeOut(animationSpec = tween(durationMillis = 400, easing = EaseInOut))
            }) {
            composable("main") {
              RefreshableScreen(mainViewModel, navController, swipeEnabled = true) {
                MainContent(mainViewModel, navController)
              }
            }
            if (mainViewModel.isAb) {
              composable("slot_a", content = slotContentA)
              composable("slot_a/flash", content = slotFlashContentA)
              composable("slot_a/flash/ak3", content = slotFlashContentA)
              composable("slot_a/flash/image", content = slotFlashContentA)
              composable("slot_a/flash/image/flash", content = slotFlashContentA)
              composable("slot_a/backup", content = slotFlashContentA)
              composable("slot_a/backup/backup", content = slotFlashContentA)
              composable("slot_a/backups", content = slotBackupsContentA)
              composable("slot_a/backups/{backupId}", content = slotBackupsContentA)
              composable("slot_a/backups/{backupId}/restore", content = slotBackupsContentA)
              composable("slot_a/backups/{backupId}/restore/restore", content = slotBackupsContentA)
              composable("slot_a/backups/{backupId}/flash/ak3", content = slotBackupFlashContentA)

              composable("slot_b", content = slotContentB)
              composable("slot_b/flash", content = slotFlashContentB)
              composable("slot_b/flash/ak3", content = slotFlashContentB)
              composable("slot_b/flash/image", content = slotFlashContentB)
              composable("slot_b/flash/image/flash", content = slotFlashContentB)
              composable("slot_b/backup", content = slotFlashContentB)
              composable("slot_b/backup/backup", content = slotFlashContentB)
              composable("slot_b/backups", content = slotBackupsContentB)
              composable("slot_b/backups/{backupId}", content = slotBackupsContentB)
              composable("slot_b/backups/{backupId}/restore", content = slotBackupsContentB)
              composable("slot_b/backups/{backupId}/restore/restore", content = slotBackupsContentB)
              composable("slot_b/backups/{backupId}/flash/ak3", content = slotBackupFlashContentB)
            } else {
              composable("slot", content = slotContent)
              composable("slot/flash", content = slotFlashContent)
              composable("slot/flash/ak3", content = slotFlashContent)
              composable("slot/flash/image", content = slotFlashContent)
              composable("slot/flash/image/flash", content = slotFlashContent)
              composable("slot/backup", content = slotFlashContent)
              composable("slot/backup/backup", content = slotFlashContent)
              composable("slot/backups", content = slotBackupsContent)
              composable("slot/backups/{backupId}", content = slotBackupsContent)
              composable("slot/backups/{backupId}/restore", content = slotBackupsContent)
              composable("slot/backups/{backupId}/restore/restore", content = slotBackupsContent)
              composable("slot/backups/{backupId}/flash/ak3", content = slotBackupFlashContent)
            }
            composable("backups") {
              backupsViewModel.clearCurrent()
              RefreshableScreen(mainViewModel, navController) {
                BackupsContent(backupsViewModel, navController)
              }
            }
            composable("backups/{backupId}") { backStackEntry ->
              backupsViewModel.currentBackup = backStackEntry.arguments?.getString("backupId")
              if (backupsViewModel.backups.containsKey(backupsViewModel.currentBackup)) {
                RefreshableScreen(mainViewModel, navController) {
                  BackupsContent(backupsViewModel, navController)
                }
              }
            }
            composable("updates") {
              updatesViewModel.clearCurrent()
              RefreshableScreen(mainViewModel, navController) {
                UpdatesContent(updatesViewModel, navController)
              }
            }
            composable("updates/add") {
              RefreshableScreen(mainViewModel, navController) {
                UpdatesAddContent(updatesViewModel, navController)
              }
            }
            composable("updates/view/{updateId}") { backStackEntry ->
              val updateId = backStackEntry.arguments?.getString("updateId")!!.toInt()
              val currentUpdate = updatesViewModel.updates.firstOrNull { it.id == updateId }
              updatesViewModel.currentUpdate = currentUpdate
              if (updatesViewModel.currentUpdate != null) {
                // TODO: enable swipe refresh
                RefreshableScreen(mainViewModel, navController) {
                  UpdatesViewContent(updatesViewModel, navController)
                }
              }
            }
            composable("updates/view/{updateId}/changelog") { backStackEntry ->
              val updateId = backStackEntry.arguments?.getString("updateId")!!.toInt()
              val currentUpdate = updatesViewModel.updates.firstOrNull { it.id == updateId }
              updatesViewModel.currentUpdate = currentUpdate
              if (updatesViewModel.currentUpdate != null) {
                RefreshableScreen(mainViewModel, navController) {
                  UpdatesChangelogContent(updatesViewModel, navController)
                }
              }
            }
            composable("reboot") {
              RefreshableScreen(mainViewModel, navController) {
                RebootContent(rebootViewModel)
              }
            }
            composable("error/{error}") { backStackEntry ->
              val error = backStackEntry.arguments?.getString("error")
              ErrorScreen(error!!)
            }
          }
        } else {
          ErrorScreen(mainViewModel.error)
        }

        if (showExitDialog) {
          AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit?") },
            confirmButton = {
              TextButton(onClick = {
                (context as? Activity)?.let {
                  it.finishAffinity()
                  exitProcess(0)
                }
              }) {
                Text("Yes")
              }
            },
            dismissButton = {
              TextButton(onClick = { showExitDialog = false }) {
                Text("No")
              }
            }
          )
        }
      }
    }
  }

  public override fun onResume() {
    super.onResume()
    if (this::mainListener.isInitialized) {
      if (!isAwaitingResult) {
        mainListener.resume()
      }
    }
  }
}