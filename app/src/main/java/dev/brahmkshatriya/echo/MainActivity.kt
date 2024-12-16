package dev.brahmkshatriya.echo

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.forEach
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.brahmkshatriya.echo.ExtensionOpenerActivity.Companion.openExtensionInstaller
import dev.brahmkshatriya.echo.databinding.ActivityMainBinding
import dev.brahmkshatriya.echo.ui.common.openItemFragmentFromUri
import dev.brahmkshatriya.echo.ui.settings.LookFragment.Companion.NAVBAR_GRADIENT
import dev.brahmkshatriya.echo.utils.animateTranslation
import dev.brahmkshatriya.echo.utils.checkAudioPermissions
import dev.brahmkshatriya.echo.utils.createNavDrawable
import dev.brahmkshatriya.echo.utils.emit
import dev.brahmkshatriya.echo.utils.listenFuture
import dev.brahmkshatriya.echo.utils.observe
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel
import dev.brahmkshatriya.echo.viewmodels.PlayerViewModel
import dev.brahmkshatriya.echo.viewmodels.PlayerViewModel.Companion.connectPlayerToUI
import dev.brahmkshatriya.echo.viewmodels.SnackBar.Companion.configureSnackBar
import dev.brahmkshatriya.echo.viewmodels.UiViewModel
import dev.brahmkshatriya.echo.viewmodels.UiViewModel.Companion.isNightMode
import dev.brahmkshatriya.echo.viewmodels.UiViewModel.Companion.setupPlayerBehavior
import kotlin.math.max
import kotlin.math.min


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val uiViewModel by viewModels<UiViewModel>()
    private val playerViewModel by viewModels<PlayerViewModel>()
    private val extensionViewModel by viewModels<ExtensionViewModel>()

    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        enableEdgeToEdge(
            SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            if (isNightMode()) SystemBarStyle.dark(TRANSPARENT)
            else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        )

        checkAudioPermissions()

        val navView = binding.navView as NavigationBarView

        navView.setOnItemSelectedListener {
            uiViewModel.navigation.value = uiViewModel.navIds.indexOf(it.itemId)
            true
        }
        navView.setOnItemReselectedListener {
            emit(uiViewModel.navigationReselected) { uiViewModel.navIds.indexOf(it.itemId) }
        }

        val isRail = binding.navView is NavigationRailView

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            uiViewModel.setSystemInsets(this, insets)
            val navBarSize = uiViewModel.systemInsets.value.bottom
            val full = playerViewModel.settings.getBoolean(NAVBAR_GRADIENT, true)
            navView.createNavDrawable(isRail, navBarSize, !full)
            insets
        }

        supportFragmentManager.addOnBackStackChangedListener {
            uiViewModel.isMainFragment.value = supportFragmentManager.backStackEntryCount == 0
        }

        fun applyNav(animate: Boolean) {
            val isMainFragment = uiViewModel.isMainFragment.value
            val insets =
                uiViewModel.setPlayerNavViewInsets(this, isMainFragment, isRail)
            val isPlayerCollapsed = uiViewModel.playerSheetState.value != STATE_EXPANDED
            navView.animateTranslation(isRail, isMainFragment, isPlayerCollapsed, animate) {
                uiViewModel.setNavInsets(insets)
            }
        }

        applyNav(false)
        observe(uiViewModel.navigation) { navView.selectedItemId = uiViewModel.navIds[it] }
        observe(uiViewModel.isMainFragment) { applyNav(true) }
        observe(uiViewModel.playerSheetState) {
            uiViewModel.setPlayerInsets(this, it != STATE_HIDDEN)
        }

        val collapsedPlayerHeight = resources.getDimension(R.dimen.bottom_player_peek_height)
        observe(uiViewModel.playerSheetOffset) {
            if (it != 0f && isRail)
                navView.translationY = -(1 + min(it, 0f)) * collapsedPlayerHeight
            if (!uiViewModel.isMainFragment.value) return@observe
            val offset = max(0f, it)
            if (isRail) navView.translationX = -navView.width * offset
            else navView.translationY = navView.height * offset
            navView.menu.forEach { item ->
                findViewById<View>(item.itemId).apply {
                    translationX = 0f
                    translationY = 0f
                }
            }
        }

        setupPlayerBehavior(uiViewModel, binding.playerFragmentContainer)
        configureSnackBar(binding.navView)

        val sessionToken =
            SessionToken(application, ComponentName(application, PlayerService::class.java))
        val playerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        listenFuture(playerFuture) {
            val player = it.getOrElse { e ->
                e.printStackTrace()
                return@listenFuture
            }
            connectPlayerToUI(player, playerViewModel)
        }

        controllerFuture = playerFuture

        extensionViewModel.updateExtensions(this)

        addOnNewIntentListener { onIntent(it) }
        onIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaBrowser.releaseFuture(it) }
    }

    private fun onIntent(intent: Intent?) {
        this.intent = null
        intent ?: return
        val fromNotif = intent.hasExtra("fromNotification")
        if (fromNotif) uiViewModel.fromNotification.value = true
        else {
            val uri = intent.data
            when (uri?.scheme) {
                "echo" -> openItemFragmentFromUri(uri)
                "file" -> openExtensionInstaller(uri)
            }
        }
    }
}