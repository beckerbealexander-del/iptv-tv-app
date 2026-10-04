package com.tivizone.player.ui.player

import android.app.Activity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerSleepTimer(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val buttons: List<Button>,
    private val getPlayer: () -> ExoPlayer?
) {
    constructor(
        activity: Activity,
        scope: CoroutineScope,
        btnSleepTimer: Button,
        getPlayer: () -> ExoPlayer?
    ) : this(activity, scope, listOf(btnSleepTimer), getPlayer)

    val optionsMinutes = listOf(0, 15, 30, 45, 60, 90, 120)
    val optionsLabels = arrayOf(
        "Aus",
        "15 Minuten",
        "30 Minuten",
        "45 Minuten",
        "60 Minuten",
        "90 Minuten",
        "120 Minuten"
    )

    private var selectedIndex = 0
    private var sleepTimerJob: Job? = null
    private var sleepTimerRemainingSeconds = 0
    var activeDialog: AlertDialog? = null
        private set

    private fun updateButtonText(text: String) {
        buttons.forEach { it.text = text }
    }

    fun isDialogShowing(): Boolean = activeDialog?.isShowing == true

    fun dismissActiveDialog(): Boolean {
        if (isDialogShowing()) {
            activeDialog?.dismiss()
            activeDialog = null
            return true
        }
        return false
    }

    fun showSelectionDialog(onResetInactivity: () -> Unit, onDismissed: () -> Unit) {
        val dialog = AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("⏱️ Sleep Timer auswählen")
            .setSingleChoiceItems(optionsLabels, selectedIndex) { d, which ->
                setSleepTimer(which)
                d.dismiss()
            }
            .setOnDismissListener {
                activeDialog = null
                onDismissed()
            }
            .create()

        activeDialog = dialog
        dialog.show()
        onResetInactivity()
    }

    fun setSleepTimer(index: Int) {
        if (index < 0 || index >= optionsMinutes.size) return
        selectedIndex = index
        val minutes = optionsMinutes[index]
        sleepTimerJob?.cancel()

        if (minutes == 0) {
            sleepTimerRemainingSeconds = 0
            updateButtonText("⏱️ Sleep: Aus")
            Toast.makeText(activity, "⏱️ Sleep Timer deaktiviert", Toast.LENGTH_SHORT).show()
        } else {
            sleepTimerRemainingSeconds = minutes * 60
            updateButtonText("⏱️ $minutes Min")
            Toast.makeText(activity, "⏱️ Sleep Timer auf $minutes Minuten gestellt", Toast.LENGTH_SHORT).show()

            sleepTimerJob = scope.launch {
                while (sleepTimerRemainingSeconds > 0) {
                    delay(1000)
                    sleepTimerRemainingSeconds--
                    val remMin = (sleepTimerRemainingSeconds + 59) / 60
                    updateButtonText("⏱️ $remMin Min")
                }
                onSleepTimerTriggered()
            }
        }
    }

    private fun onSleepTimerTriggered() {
        activity.runOnUiThread {
            Toast.makeText(activity, "⏱️ Sleep Timer abgelaufen – Standby wird eingeleitet", Toast.LENGTH_LONG).show()
            val player = getPlayer()
            player?.stop()
            player?.clearMediaItems()
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.finishAffinity()
        }
    }

    fun cancel() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        dismissActiveDialog()
    }
}
