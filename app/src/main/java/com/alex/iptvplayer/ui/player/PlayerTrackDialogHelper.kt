package com.alex.iptvplayer.ui.player

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer

class PlayerTrackDialogHelper {
    var activeDialog: AlertDialog? = null
        private set

    fun isDialogShowing(): Boolean = activeDialog?.isShowing == true

    fun dismissActiveDialog(): Boolean {
        if (isDialogShowing()) {
            activeDialog?.dismiss()
            activeDialog = null
            return true
        }
        return false
    }

    fun showAudioTrackDialog(
        activity: Activity,
        player: ExoPlayer?,
        onTrackSelected: () -> Unit,
        onDismissed: () -> Unit
    ) {
        val p = player ?: return
        val tracks = p.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isEmpty()) {
            Toast.makeText(activity, "Keine alternativen Tonspuren verfügbar", Toast.LENGTH_SHORT).show()
            return
        }

        val names = mutableListOf<String>()
        var selectedIdx = 0
        audioGroups.forEachIndexed { idx, g ->
            val f = g.getTrackFormat(0)
            val lang = f.language ?: "Spur ${idx + 1}"
            val channels = if (f.channelCount > 2) "${f.channelCount}.1" else "Stereo"
            val label = f.label ?: ""
            names.add("$lang $label ($channels)".trim())
            if (g.isSelected) selectedIdx = idx
        }

        val dialog = AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Tonspur auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { d, which ->
                val group = audioGroups[which]
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                d.dismiss()
                onTrackSelected()
                Toast.makeText(activity, "Tonspur gewählt: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            onDismissed()
        }
        dialog.show()
    }

    fun showSubtitleDialog(
        activity: Activity,
        player: ExoPlayer?,
        onTrackSelected: () -> Unit,
        onDismissed: () -> Unit
    ) {
        val p = player ?: return
        val tracks = p.currentTracks
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val names = mutableListOf("Aus (Deaktiviert)")
        var selectedIdx = 0
        textGroups.forEachIndexed { idx, g ->
            val f = g.getTrackFormat(0)
            val lang = f.language ?: "Untertitel ${idx + 1}"
            names.add(lang)
            if (g.isSelected) selectedIdx = idx + 1
        }

        val dialog = AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Untertitel auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { d, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    val group = textGroups[which - 1]
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                }
                d.dismiss()
                onTrackSelected()
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            onDismissed()
        }
        dialog.show()
    }
}
