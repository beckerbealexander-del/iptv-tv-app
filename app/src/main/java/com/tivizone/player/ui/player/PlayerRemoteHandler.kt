package com.tivizone.player.ui.player

import android.view.KeyEvent
import android.view.View
import com.tivizone.player.databinding.ActivityPlayerBinding

class PlayerRemoteHandler(
    private val binding: ActivityPlayerBinding,
    private val osdController: PlayerOsdController,
    private val scrubberHelper: PlayerScrubberHelper,
    private val isAnyDialogShowing: () -> Boolean,
    private val dismissAnyDialog: () -> Boolean,
    private val isLive: () -> Boolean,
    private val onPlayPause: () -> Unit,
    private val onPerformScrub: (forward: Boolean) -> Unit,
    private val onCommitScrub: () -> Unit,
    private val onZapNext: () -> Unit,
    private val onZapPrev: () -> Unit,
    private val onCycleSourceNext: () -> Unit,
    private val onCycleSourcePrev: () -> Unit,
    private val onToggleStats: () -> Unit,
    private val onOpenSleepTimerMenu: () -> Unit,
    private val onPlayPrevEpisode: () -> Unit,
    private val onPlayNextEpisode: () -> Unit,
    private val onShowAudioTrackDialog: () -> Unit,
    private val onShowSubtitleDialog: () -> Unit,
    private val onShowOsd: () -> Unit,
    private val onFinish: () -> Unit
) {

    fun setupKeyListeners() {
        val buttonFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                osdController.lastFocusedOsdButton = v
                osdController.resetOsdInactivityTimer(isAnyDialogShowing)
            }
        }
        binding.btnAudioTracks.onFocusChangeListener = buttonFocusChangeListener
        binding.btnSubtitles.onFocusChangeListener = buttonFocusChangeListener
        binding.btnPrevEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnNextEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnDebugOverlay.onFocusChangeListener = buttonFocusChangeListener
        binding.btnSleepTimer.onFocusChangeListener = buttonFocusChangeListener
        binding.btnLivePlayerSleepTimer.onFocusChangeListener = buttonFocusChangeListener

        val buttonKeyHandler = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                osdController.resetOsdInactivityTimer(isAnyDialogShowing)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.playerSeekBar.requestFocus()
                        return@OnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> return@OnKeyListener true
                }
            }
            false
        }
        binding.btnAudioTracks.setOnKeyListener(buttonKeyHandler)
        binding.btnSubtitles.setOnKeyListener(buttonKeyHandler)
        binding.btnPrevEpisode.setOnKeyListener(buttonKeyHandler)
        binding.btnNextEpisode.setOnKeyListener(buttonKeyHandler)
        binding.btnDebugOverlay.setOnKeyListener(buttonKeyHandler)
        binding.btnSleepTimer.setOnKeyListener(buttonKeyHandler)

        binding.btnLivePlayerSleepTimer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                osdController.resetOsdInactivityTimer(isAnyDialogShowing)
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    osdController.hideOsd()
                    return@setOnKeyListener true
                }
            }
            false
        }

        binding.playerSeekBar.setOnKeyListener { _, keyCode, event ->
            osdController.resetOsdInactivityTimer(isAnyDialogShowing)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        osdController.hideOsd()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        osdController.focusOsdButtonRow()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onPerformScrub(false)
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onPerformScrub(true)
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && scrubberHelper.isScrubbing && scrubberHelper.targetSeekPosition >= 0) {
                        onCommitScrub()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        osdController.resetOsdInactivityTimer(isAnyDialogShowing)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (dismissAnyDialog()) {
                    return true
                }
                if (scrubberHelper.isScrubbing) {
                    scrubberHelper.cancelScrub()
                    osdController.hideOsd()
                    return true
                }
                if (isLive() && binding.layoutLiveOsd.visibility == View.VISIBLE) {
                    osdController.hideOsd()
                    return true
                }
                if (binding.osdBottom.visibility == View.VISIBLE) {
                    osdController.hideOsd()
                    return true
                }
                onFinish()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_I -> {
                onToggleStats()
                return true
            }
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isLive()) {
                    onCycleSourceNext()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isLive()) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        onCycleSourceNext()
                        return true
                    }
                } else if (osdController.isOsdButtonFocused()) {
                    return false
                } else {
                    onPerformScrub(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isLive()) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        onCycleSourcePrev()
                        return true
                    }
                } else if (osdController.isOsdButtonFocused()) {
                    return false
                } else {
                    onPerformScrub(false)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive()) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE && !binding.btnLivePlayerSleepTimer.hasFocus()) {
                        binding.btnLivePlayerSleepTimer.requestFocus()
                        return true
                    }
                    onZapPrev()
                    return true
                } else {
                    if (binding.osdBottom.visibility != View.VISIBLE) {
                        onShowOsd()
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        osdController.focusOsdButtonRow()
                    } else if (osdController.isOsdButtonFocused()) {
                        // Focus behalten
                    } else {
                        osdController.focusOsdButtonRow()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive()) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE && binding.btnLivePlayerSleepTimer.hasFocus()) {
                        osdController.hideOsd()
                        return true
                    }
                    onZapNext()
                    return true
                } else {
                    if (binding.osdBottom.visibility != View.VISIBLE) {
                        onShowOsd()
                        binding.playerSeekBar.requestFocus()
                    } else if (osdController.isOsdButtonFocused()) {
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        osdController.hideOsd()
                    } else {
                        binding.playerSeekBar.requestFocus()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isLive()) {
                    if (binding.btnLivePlayerSleepTimer.hasFocus()) {
                        onOpenSleepTimerMenu()
                        return true
                    }
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        osdController.hideOsd()
                    } else {
                        onShowOsd()
                    }
                    return true
                } else {
                    if (scrubberHelper.isScrubbing && scrubberHelper.targetSeekPosition >= 0) {
                        onCommitScrub()
                        return true
                    }
                    if (binding.btnAudioTracks.hasFocus()) {
                        onShowAudioTrackDialog()
                        return true
                    } else if (binding.btnSubtitles.hasFocus()) {
                        onShowSubtitleDialog()
                        return true
                    } else if (binding.btnPrevEpisode.hasFocus()) {
                        onPlayPrevEpisode()
                        return true
                    } else if (binding.btnNextEpisode.hasFocus()) {
                        onPlayNextEpisode()
                        return true
                    } else if (binding.btnDebugOverlay.hasFocus()) {
                        onToggleStats()
                        return true
                    } else if (binding.btnSleepTimer.hasFocus()) {
                        onOpenSleepTimerMenu()
                        return true
                    } else {
                        onPlayPause()
                    }
                    return true
                }
            }
        }
        return false
    }
}
