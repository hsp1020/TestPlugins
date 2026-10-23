package com.SyncCS

import android.app.Dialog
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SyncSettingsFragment : DialogFragment() {
    private var statusLabel: TextView? = null
    private var accountDialog: AlertDialog? = null
    private var githubLoginJob: Job? = null
    private data class SyncProgress(val dialog: AlertDialog, val bar: ProgressBar, val label: TextView)

    private fun showSyncProgress(): SyncProgress {
        val context = requireContext()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 18))
        }
        val spinner = ProgressBar(context).apply { isIndeterminate = true }
        body.addView(spinner, LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)))
        val text = TextView(context).apply {
            text = "Synchronizing..."
            textSize = 16f
            setPadding(dp(context, 18), 0, 0, 0)
        }
        body.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dialog = AlertDialog.Builder(context)
            .setTitle("SyncCS")
            .setView(body)
            .setCancelable(false)
            .create()
        dialog.show()
        return SyncProgress(dialog, spinner, text)
    }

    private suspend fun finishSyncProgress(
        progress: SyncProgress, result: String, restartAfter: Boolean = false
    ): Boolean {
        val success = result.startsWith("Synced:")
        statusLabel?.text = result
        if (success) {
            progress.bar.visibility = View.GONE
            progress.label.text = if (result.startsWith("Synced: downloaded 0, uploaded 0")) {
                "Sync complete · Already up to date"
            } else {
                "Sync complete"
            }
            delay(1500L)
            if (restartAfter) {
                progress.label.text = "Restarting CloudStream..."
                // Keep the dialog visible long enough for this transition to render.
                delay(850L)
            } else {
                progress.dialog.dismiss()
            }
        } else {
            progress.dialog.dismiss()
            if (isAdded) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Sync failed")
                    .setMessage(result)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
        return success
    }


    // Device Flow has no OAuth redirect. Bring our existing task forward once
    // GitHub confirms the code. This removes an authorization tab when the
    // browser shares our task; Android may block cross-task foreground jumps.
    private fun returnToCloudStreamAfterAuthorization(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        try {
            host.startActivity(Intent(host, host.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (_: Exception) {
            // An external browser owns its own task; it cannot be closed by an
            // extension. The user can return with Android Back or Recents.
        }
    }

    private fun restartCloudStreamAfterFirstSignIn(context: Context): Boolean {
        val app = context.applicationContext
        val launchComponent = app.packageManager
            .getLaunchIntentForPackage(app.packageName)?.component ?: return false
        return try {
            // Match StremioC's settings-import restart. Only reached after the
            // first successful GitHub sign-in AND completed sync.
            app.startActivity(Intent.makeRestartActivityTask(launchComponent))
            Runtime.getRuntime().exit(0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 12))
    }

    private fun secondary(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 13f
            alpha = 0.78f
        }

    private fun scroll(context: Context, body: View): ScrollView =
        ScrollView(context).apply {
            isFillViewport = false
            addView(body)
        }

    private fun settingRow(
        context: Context,
        title: String,
        description: String,
        action: (LinearLayout) -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        minimumHeight = dp(context, 60)
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            && ripple.resourceId != 0) {
            setBackgroundResource(ripple.resourceId)
        }
        addView(TextView(context).apply {
            text = title
            textSize = 16f
        })
        addView(secondary(context, description))
        isClickable = true
        isFocusable = true
        contentDescription = "$title. $description"
        setOnClickListener { action(this) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val config = SyncConfiguration(context)
        val body = column(context)

        val status = secondary(context, "").apply {
            text = statusText(config)
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 12))
        }
        statusLabel = status
        body.addView(status)

        body.addView(settingRow(
            context,
            "Sync account",
            "Direct encrypted backup in your private GitHub repository"
        ) {
            showAccountDialog()
        })

        body.addView(settingRow(
            context,
            "Sync now",
            "Synchronize library, accounts and extensions"
        ) { row ->
            val host = requireActivity()
            row.isEnabled = false
            lifecycleScope.launch {
                val progress = showSyncProgress()
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        SyncCoordinator.synchronize(host.applicationContext, host)
                    }
                    if (finishSyncProgress(progress, outcome) && isAdded) {
                        dismissAllowingStateLoss()
                    }
                } catch (cancelled: CancellationException) {
                    progress.dialog.dismiss()
                    throw cancelled
                } catch (error: Exception) {
                    finishSyncProgress(
                        progress, "Sync failed: " + (error.message ?: error.javaClass.simpleName).take(160)
                    )
                } finally {
                    row.isEnabled = true
                }
            }
        })

        body.addView(settingRow(
            context,
            "Create GitHub user backup",
            "Save a dated restore point after verified synchronization"
        ) {
            confirmManualBackup()
        })

        body.addView(settingRow(
            context,
            "Restore backups",
            "Device originals, GitHub user backups and automatic history"
        ) {
            showBackupHistory()
        })

        body.addView(settingRow(
            context,
            "Disconnect this device",
            "Remove this device's sync session only"
        ) {
            AlertDialog.Builder(context)
                .setTitle("Disconnect SyncCS")
                .setMessage("Disconnect this device? Cloud backups and data on other devices will be preserved.")
                .setPositiveButton("Disconnect") { _, _ ->
                    if (LocalSyncJournal.pending(context)) {
                        AlertDialog.Builder(context)
                            .setTitle("Local rollback must finish")
                            .setMessage("A previous restore did not complete. Open CloudStream " +
                                "in the foreground and complete recovery before disconnecting.")
                            .setPositiveButton("Close", null).show()
                    } else {
                        config.clearAccount()
                        statusLabel?.text = statusText(config)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })

        body.addView(secondary(
            context,
            "Shared: library, accounts, extensions, source priorities.\n" +
                "Device-only: appearance, decoder, subtitle display."
        ).apply {
            setPadding(dp(context, 12), dp(context, 14), dp(context, 12), dp(context, 2))
        })

        return AlertDialog.Builder(context)
            .setTitle("SyncCS")
            .setView(scroll(context, body))
            .setPositiveButton("Close", null)
            .create()
    }

    private fun statusText(config: SyncConfiguration): String {
        val status = if (SyncAuthGuard.needsReauthentication(config)) {
            "GitHub authorization expired or revoked (HTTP 401). Open Sync account to reauthorize."
        } else if (config.preferences.getBoolean("github_recovery_in_progress", false)) {
            "Backup revision recovery in progress"
        } else if (config.active()) SyncCoordinator.lastStatus else "Not connected"
        return "$status  ·  ${config.deviceName}"
    }

    private class EyeIcon(private val iconColor: Int, private val visible: Boolean, private val iconSize: Int) : Drawable() {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = iconColor
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val scale = minOf(bounds.width(), bounds.height()) / 24f
            val saved = canvas.save()
            canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale)
            canvas.scale(scale, scale)
            val outline = Path().apply {
                moveTo(2f, 12f)
                cubicTo(5f, 8f, 8f, 6f, 12f, 6f)
                cubicTo(16f, 6f, 19f, 8f, 22f, 12f)
                cubicTo(19f, 16f, 16f, 18f, 12f, 18f)
                cubicTo(8f, 18f, 5f, 16f, 2f, 12f)
                close()
            }
            canvas.drawPath(outline, pen)
            canvas.drawCircle(12f, 12f, 3f, pen)
            if (!visible) canvas.drawLine(3f, 21f, 21f, 3f, pen)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            pen.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            pen.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = iconSize
        override fun getIntrinsicHeight(): Int = iconSize
    }

    private fun labeledField(
        context: Context,
        body: LinearLayout,
        title: String,
        hint: String,
        value: String = "",
        kind: Int = InputType.TYPE_CLASS_TEXT,
        done: Boolean = false,
        revealToggle: Boolean = false
    ): EditText {
        body.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setPadding(0, dp(context, 12), 0, dp(context, 2))
        })
        val field = EditText(context).apply {
            inputType = kind
            setSingleLine(true)
            if ((kind and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            textSize = 16f
            this.hint = hint
            setText(value)
            imeOptions = if (done) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        }
        if (revealToggle) {
            val line = FrameLayout(context)
            val textParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            line.addView(field, textParams)

            // Reserve space within the native text field; keep its underline across the row.
            field.setPadding(
                field.paddingLeft, field.paddingTop,
                field.paddingRight + dp(context, 48), field.paddingBottom
            )
            var revealed = false
            val toggle = ImageButton(context).apply {
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, ripple, true
                    ) && ripple.resourceId != 0
                ) {
                    setBackgroundResource(ripple.resourceId)
                } else {
                    background = null
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Show recovery passphrase"
                setImageDrawable(EyeIcon(field.currentTextColor, false, dp(context, 24)))
                setOnClickListener {
                    val selectionStart = field.selectionStart.coerceAtLeast(0)
                    val selectionEnd = field.selectionEnd.coerceAtLeast(0)
                    revealed = !revealed
                    field.transformationMethod =
                        if (revealed) null else PasswordTransformationMethod.getInstance()
                    val length = field.text?.length ?: 0
                    field.setSelection(
                        selectionStart.coerceAtMost(length),
                        selectionEnd.coerceAtMost(length)
                    )
                    setImageDrawable(EyeIcon(field.currentTextColor, revealed, dp(context, 24)))
                    contentDescription =
                        if (revealed) "Hide recovery passphrase" else "Show recovery passphrase"
                }
            }
            line.addView(toggle, FrameLayout.LayoutParams(
                dp(context, 48), dp(context, 48),
                Gravity.END or Gravity.CENTER_VERTICAL
            ))
            body.addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        } else {
            body.addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return field
    }

    private fun confirmManualBackup() {
        val host = requireActivity()
        val config = SyncConfiguration(host)
        if (!config.active() || SyncAuthGuard.needsReauthentication(config) ||
            LocalSyncJournal.pending(host) ||
            config.preferences.getBoolean("github_recovery_in_progress", false)) {
            AlertDialog.Builder(host).setTitle("GitHub account not ready")
                .setMessage("Connect GitHub and complete any pending recovery before creating a user backup.")
                .setPositiveButton("Close", null).show()
            return
        }
        AlertDialog.Builder(host).setTitle("Create GitHub user backup?")
            .setMessage("First synchronize and verify all changes, then save a permanent " +
                "restore point in your private GitHub repository. This does not create " +
                "a second copy of the data or replace existing backups.")
            .setPositiveButton("Back up now") { _, _ ->
                lifecycleScope.launch {
                    val progress = showSyncProgress()
                    try {
                        val checkpoint = withContext(Dispatchers.IO) {
                            val result = SyncCoordinator.synchronize(host.applicationContext, host)
                            check(result.startsWith("Synced:")) {
                                "Cannot create user backup: $result"
                            }
                            check(!SyncAuthGuard.needsReauthentication(config) &&
                                !config.preferences.getBoolean("github_recovery_in_progress", false)) {
                                "GitHub authorization or recovery is incomplete."
                            }
                            val encryptedToken = config.preferences.getString("github_token", null)
                                ?: error("Missing GitHub token")
                            val store = GithubBackupStore(
                                String(LocalVault.decrypt(encryptedToken), Charsets.UTF_8),
                                config.preferences.getString("github_login", null)
                                    ?: error("Missing GitHub account"),
                                deviceName = config.deviceName, deviceId = config.deviceId)
                            val snapshot = store.current(SyncCipher.saved(config))
                                ?: error("No verified GitHub backup is available.")
                            check(config.preferences.getString("github_base_revision", null)
                                == snapshot.revision) {
                                "GitHub changed during user backup. Sync again before retrying."
                            }
                            store.createManualBackup(snapshot)
                        }
                        progress.dialog.dismiss()
                        AlertDialog.Builder(host).setTitle("GitHub user backup saved")
                            .setMessage("Restore point: " + checkpoint.timestamp +
                                "\nRevision: " + checkpoint.id.take(12) +
                                "\nNo existing backup was replaced.")
                            .setPositiveButton("View backups") { _, _ -> showBackupHistory() }
                            .setNegativeButton("Close", null).show()
                    } catch (cancelled: CancellationException) {
                        progress.dialog.dismiss()
                        throw cancelled
                    } catch (error: Exception) {
                        progress.dialog.dismiss()
                        SyncTrace.info("github-user-backup",
                            "event=failed type=${error.javaClass.simpleName}")
                        AlertDialog.Builder(host).setTitle("GitHub user backup failed")
                            .setMessage((error.message ?: error.javaClass.simpleName).take(300) +
                                "\nNo user backup marker was created.")
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showBackupHistory() {
        val host = requireActivity()
        if (LocalSyncJournal.pending(host)) {
            AlertDialog.Builder(host).setTitle("Local rollback pending")
                .setMessage("Finish the previous local rollback before selecting another backup.")
                .setPositiveButton("Close", null).show()
            return
        }
        val config = SyncConfiguration(host)
        lifecycleScope.launch {
            val progress = showSyncProgress()
            try {
                val local = withContext(Dispatchers.IO) { LocalOriginalBackup.list(host) }
                var historyError: String? = null
                var userBackupError: String? = null
                var remote = emptyList<GithubBackupStore.Revision>()
                var manual = emptyList<GithubBackupStore.Revision>()
                if (config.active()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val encryptedToken = config.preferences.getString("github_token", null)
                                ?: error("Missing GitHub token")
                            val store = GithubBackupStore(
                                String(LocalVault.decrypt(encryptedToken), Charsets.UTF_8),
                                config.preferences.getString("github_login", null)
                                    ?: error("Missing GitHub account"),
                                deviceName = config.deviceName, deviceId = config.deviceId)
                            val current = store.current(SyncCipher.saved(config))
                                ?: error("No GitHub backup available.")
                            try {
                                remote = store.history(current.branch, 30)
                                    .filter { it.id != current.revision &&
                                        it.summary.startsWith("SyncCS ") }
                            } catch (error: Exception) {
                                historyError = error.message ?: error.javaClass.simpleName
                            }
                            try {
                                manual = store.manualBackups()
                            } catch (error: Exception) {
                                userBackupError = error.message ?: error.javaClass.simpleName
                            }
                        } catch (error: Exception) {
                            historyError = error.message ?: error.javaClass.simpleName
                            userBackupError = historyError
                        }
                    }
                }
                progress.dialog.dismiss()
                val body = column(host)
                val picker = AlertDialog.Builder(host)
                    .setTitle("Choose backup to restore")
                    .setView(scroll(host, body))
                    .setNegativeButton("Close", null).create()
                fun heading(title: String) {
                    body.addView(secondary(host, title).apply {
                        setPadding(dp(host, 12), dp(host, 14), 0, dp(host, 4))
                    })
                }
                heading("DEVICE ORIGINAL BACKUPS")
                if (local.isEmpty()) {
                    body.addView(secondary(host, "No original backup on this device."))
                } else {
                    val formatter = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    local.forEach { entry ->
                        val date = formatter.format(java.util.Date(entry.created))
                        body.addView(settingRow(host,
                            "Original device backup · $date",
                            if (entry.complete) "Encrypted settings, profiles and archived .cs3 files"
                            else "Older encrypted original · plugin binaries may be unavailable"
                        ) {
                            picker.dismiss()
                            confirmOriginalRestore(entry)
                        })
                    }
                }
                heading("GITHUB USER BACKUPS")
                when {
                    !config.active() -> body.addView(secondary(host,
                        "Connect GitHub to view saved user backups."))
                    userBackupError != null -> body.addView(secondary(host,
                        "GitHub user backups unavailable: " + userBackupError.take(160)))
                    manual.isEmpty() -> body.addView(secondary(host,
                        "No user backups saved yet. Use Create GitHub user backup."))
                    else -> manual.forEach { revision ->
                        body.addView(settingRow(host,
                            "User backup · " + revision.timestamp,
                            "Restore point · " + revision.id.take(12))
                        ) {
                            picker.dismiss()
                            confirmGithubRestore(revision)
                        }
                    }
                }
                heading("GITHUB BACKUP HISTORY")
                when {
                    !config.active() -> body.addView(secondary(host,
                        "Connect GitHub to browse recent automatic revisions."))
                    historyError != null -> body.addView(secondary(host,
                        "GitHub history unavailable: " + historyError.take(160)))
                    remote.isEmpty() -> body.addView(secondary(host,
                        "No earlier SyncCS revisions in the latest 30 commits."))
                    else -> remote.forEach { revision ->
                        body.addView(settingRow(host,
                            "GitHub revision · " + revision.timestamp,
                            revision.summary.take(100)
                        ) {
                            picker.dismiss()
                            confirmGithubRestore(revision)
                        }
                    }
                }
                picker.show()
            } catch (cancelled: CancellationException) {
                progress.dialog.dismiss()
                throw cancelled
            } catch (error: Exception) {
                progress.dialog.dismiss()
                AlertDialog.Builder(host).setTitle("Backup list unavailable")
                    .setMessage(error.message.orEmpty().take(300))
                    .setPositiveButton("Close", null).show()
            }
        }
    }

    private fun confirmOriginalRestore(entry: LocalOriginalBackup.Entry) {
        val host = requireActivity()
        AlertDialog.Builder(host)
            .setTitle("Restore original device backup?")
            .setMessage("Restore this device's pre-sync profiles, watch history, preferences " +
                "and original plugins. GitHub backups will NOT be changed. " +
                "SyncCS will disconnect this device afterward so the restored old data " +
                "cannot be automatically uploaded to GitHub.\n\n" +
                if (!entry.complete) "This older backup does not contain plugin archives. " +
                    "If required binaries are unavailable, restoration will stop without changes."
                else "The current device state will be saved to a rollback journal first.")
            .setPositiveButton("Restore original") { _, _ ->
                lifecycleScope.launch {
                    val progress = showSyncProgress()
                    try {
                        val result = withContext(Dispatchers.IO) {
                            SyncCoordinator.restoreOriginal(host.applicationContext, host, entry)
                        }
                        progress.dialog.dismiss()
                        statusLabel?.text = result
                        AlertDialog.Builder(host).setTitle("Original backup restored")
                            .setMessage(result + "\n\nRestart CloudStream to reload the restored plugins.")
                            .setPositiveButton("Restart") { _, _ ->
                                restartCloudStreamAfterFirstSignIn(host)
                            }
                            .setNegativeButton("Later", null).show()
                    } catch (error: Exception) {
                        progress.dialog.dismiss()
                        AlertDialog.Builder(host).setTitle("Original restore failed")
                            .setMessage((error.message ?: error.javaClass.simpleName).take(350) +
                                "\nGitHub history was not changed. If local rollback failed, " +
                                "SyncCS will block normal syncing.")
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmGithubRestore(selected: GithubBackupStore.Revision) {
        val host = requireActivity()
        val config = SyncConfiguration(host)
        AlertDialog.Builder(host)
            .setTitle("Restore this GitHub revision?")
            .setMessage("This creates a NEW Git commit preserving full history, " +
                "increments recovery generation, and replaces synchronized settings. " +
                "Other devices must reconnect before uploading. " +
                "Revision: " + selected.id.take(12))
            .setPositiveButton("Restore") { _, _ ->
                lifecycleScope.launch {
                    val progress = showSyncProgress()
                    try {
                        val result = withContext(Dispatchers.IO) {
                            check(config.preferences.edit()
                                .putBoolean("github_recovery_in_progress", true).commit())
                            try {
                                val token = config.preferences.getString("github_token", null)
                                    ?: error("Missing GitHub access token")
                                val store = GithubBackupStore(
                                    String(LocalVault.decrypt(token), Charsets.UTF_8),
                                    config.preferences.getString("github_login", null)
                                        ?: error("Missing GitHub login"),
                                    deviceName = config.deviceName, deviceId = config.deviceId)
                                val cipher = SyncCipher.saved(config)
                                val current = store.current(cipher)
                                    ?: error("No current GitHub backup.")
                                val newRevision = store.rollback(current, selected.id, cipher)
                                val editor = config.preferences.edit()
                                    .putInt("github_generation", current.generation + 1)
                                    .putString("github_base_revision", newRevision)
                                for (key in listOf("base", "remote_cursor", "remote_versions",
                                    "profile_baseline", "profile_remote_snapshot",
                                    "plugin_baseline", "plugin_remote_snapshot",
                                    "pending_plugins", "list_baselines", "pending_conflicts"
                                )) editor.remove(key)
                                for (key in config.preferences.all.keys) {
                                    if (key.startsWith("modified/")) editor.remove(key)
                                }
                                check(editor.commit())
                            } finally {
                                config.preferences.edit().remove("github_recovery_in_progress").commit()
                            }
                            SyncCoordinator.synchronize(host.applicationContext, host)
                        }
                        finishSyncProgress(progress, result)
                    } catch (error: Exception) {
                        progress.dialog.dismiss()
                        AlertDialog.Builder(host).setTitle("GitHub recovery not completed")
                            .setMessage(error.message.orEmpty().take(300))
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAccountDialog() {
        if (accountDialog?.isShowing == true) return
        val context = requireContext()
        if (LocalSyncJournal.pending(context)) {
            AlertDialog.Builder(context).setTitle("Local rollback pending")
                .setMessage("Finish recovering the previous local restore before linking an account.")
                .setPositiveButton("Close", null).show()
            return
        }
        val config = SyncConfiguration(context)
        val body = column(context)
        body.addView(secondary(context,
            "Direct GitHub backup · Your account owns a private repository. " +
            "Use the Client ID of a GitHub OAuth App with Device Flow enabled. " +
            "Authorization requests repository access; only use an app you trust."))
        val clientId = labeledField(context, body,
            "GitHub OAuth App Client ID", "Client ID",
            config.preferences.getString("github_client_id", "") ?: "")
        val recovery = labeledField(context, body,
            "Backup recovery passphrase", "At least 12 characters",
            kind = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            revealToggle = true)
        val device = labeledField(context, body,
            "Device name", "Device name", config.deviceName, done = true)
        val feedback = secondary(context, "").apply {
            visibility = View.GONE
            setPadding(0, dp(context, 12), 0, 0)
        }
        body.addView(feedback)
        val deviceCode = TextView(context).apply {
            textSize = 36f
            setTextColor(Color.rgb(229, 57, 53))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(context, 16), 0, dp(context, 12))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "GitHub authorization code. Tap to copy."
            setOnClickListener {
                val code = text.toString()
                if (code.isNotBlank()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", code))
                    Toast.makeText(context, "GitHub code copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        body.addView(deviceCode)
        val accountScroll = scroll(context, body)
        val dialog = AlertDialog.Builder(context)
            .setTitle("SyncCS · GitHub direct")
            .setView(accountScroll)
            .setPositiveButton("Connect GitHub", null)
            .setNegativeButton("Cancel", null).create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (githubLoginJob?.isActive == true) return@setOnClickListener
                val client = clientId.text.toString().trim()
                val phrase = recovery.text.toString()
                val name = device.text.toString().trim().ifBlank { Build.MODEL }
                if (!client.matches(Regex("[A-Za-z0-9_.-]{8,80}")) || phrase.length < 12) {
                    feedback.text = "Enter your GitHub OAuth App Client ID and a recovery passphrase of 12+ characters."
                    feedback.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                button.isEnabled = false
                val host = requireActivity()
                val firstConnection = !config.active()
                feedback.visibility = View.VISIBLE
                githubLoginJob = lifecycleScope.launch {
                    var authStep = "pending-token"
                    try {
                        val savedToken = withContext(Dispatchers.IO) {
                            GithubAuthFlow.pending(config, client)
                        }
                        val accessToken = if (savedToken != null) {
                            SyncTrace.info("github-auth-step", "step=pending-token event=reused")
                            feedback.text = "Resuming GitHub setup with saved authorization..."
                            savedToken
                        } else {
                            authStep = "device-code"
                            SyncTrace.info("github-auth-step", "step=device-code event=start")
                            val challenge = withContext(Dispatchers.IO) {
                                GithubAuthFlow.retry("device-code") {
                                    GithubDeviceCode.begin(client)
                                }
                            }
                            SyncTrace.info("github-auth-step", "step=device-code event=success")
                            deviceCode.text = challenge.userCode
                            deviceCode.visibility = View.VISIBLE
                            feedback.text = "Enter this code at " + challenge.verification +
                                ". Tap the red code to copy it. " +
                                "On Android TV, use a phone or computer."
                            accountScroll.post { accountScroll.fullScroll(View.FOCUS_DOWN) }
                            try {
                                host.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse(challenge.verification)))
                            } catch (_: Exception) {
                                feedback.text = "Open " + challenge.verification +
                                    " on a browser and enter the red code above."
                            }
                            authStep = "access-token"
                            SyncTrace.info("github-auth-step", "step=access-token event=start")
                            var token: String? = null
                            var interval = challenge.pollSeconds
                            withTimeout(challenge.expiresSeconds * 1000L) {
                                while (token == null) {
                                    delay(interval * 1000L)
                                    try {
                                        token = withContext(Dispatchers.IO) {
                                            GithubAuthFlow.retry("access-token") {
                                                GithubDeviceCode.poll(client, challenge)
                                            }.also { issued ->
                                                // Persist on the IO thread before resuming the UI
                                                // coroutine, even if the Activity is then closed.
                                                if (issued != null) {
                                                    GithubAuthFlow.save(config, client, issued)
                                                }
                                            }
                                        }
                                    } catch (slow: java.io.IOException) {
                                        if (slow.message?.contains("slower polling") == true) {
                                            interval += 5
                                        } else throw slow
                                    }
                                }
                            }
                            SyncTrace.info("github-auth-step", "step=access-token event=success")
                            token ?: error("GitHub authorization did not complete.")
                        }
                        feedback.text = "GitHub authorized. Returning to CloudStream..."
                        returnToCloudStreamAfterAuthorization(host)
                        authStep = "account-profile"
                        SyncTrace.info("github-auth-step", "step=account-profile event=start")
                        val profile = withContext(Dispatchers.IO) {
                            GithubAuthFlow.retry("account-profile") {
                                GithubDeviceCode.profile(accessToken)
                            }
                        }
                        SyncTrace.info("github-auth-step", "step=account-profile event=success")
                        val previous = config.preferences.getString("github_account_id", null)
                        if (previous != null && previous != profile.id) {
                            withContext(Dispatchers.IO) { GithubAuthFlow.clear(config) }
                            error("Different GitHub account: disconnect the current device first.")
                        }
                        feedback.text = "Verifying private repository and recovery key..."
                        // No active token, sync secret or upload before backup verification.
                        authStep = "backup-verification"
                        SyncTrace.info("github-auth-step", "step=backup-verification event=start")
                        val verifiedCipher = withContext(Dispatchers.IO) {
                            SyncCipher.derive(phrase, "https://api.github.com", profile.id)
                        }
                        val existingBackup = withContext(Dispatchers.IO) {
                            GithubAuthFlow.retry("backup-verification") {
                                GithubBackupStore(accessToken, profile.login).current(verifiedCipher)
                            }
                        }
                        SyncTrace.info("github-auth-step", "step=backup-verification event=success")
                        val createFirst = if (existingBackup == null) {
                            suspendCancellableCoroutine<Boolean> { continuation ->
                                val approval = AlertDialog.Builder(host)
                                    .setTitle("Create initial GitHub backup?")
                                    .setMessage("No completed cloud backup exists. " +
                                        "Only continue on the SOURCE device whose settings " +
                                        "and watch history you want as the new master. " +
                                        "Existing device data may contain old or corrupted settings. " +
                                        "Do not create an initial backup from an untrusted device.")
                                    .setPositiveButton("Use this device as source") { _, _ ->
                                        if (continuation.isActive) continuation.resume(true)
                                    }
                                    .setNegativeButton("Cancel") { _, _ ->
                                        if (continuation.isActive) continuation.resume(false)
                                    }
                                    .setOnCancelListener {
                                        if (continuation.isActive) continuation.resume(false)
                                    }.create()
                                continuation.invokeOnCancellation { approval.dismiss() }
                                approval.show()
                            }
                        } else false
                        check(existingBackup != null || createFirst) {
                            "Initial GitHub backup canceled; no remote data modified."
                        }
                        authStep = "session-commit"
                        withContext(Dispatchers.IO) {
                            val edit = config.preferences.edit()
                                .putString("secret", verifiedCipher.encryptForLocalStorage())
                                .putString("github_token", LocalVault.encrypt(
                                    accessToken.toByteArray(Charsets.UTF_8)))
                                .putString("github_account_id", profile.id)
                                .putString("github_login", profile.login)
                                .putString("github_client_id", client)
                                .putString("sync_transport", "github")
                                .putBoolean("github_initial_backup_confirmed", createFirst)
                                .putString("device_name", name)
                                .remove("account_switch_blocked")
                                .remove("github_pending_token").remove("github_pending_client")
                            if (previous == null) {
                                edit.remove("base").remove("remote_cursor")
                                    .remove("remote_versions").remove("plugin_baseline")
                                    .remove("profile_baseline").remove("profile_remote_snapshot")
                                    .remove("plugin_remote_snapshot").remove("list_baselines")
                                    .remove("pending_plugins").remove("pending_conflicts")
                                for (key in config.preferences.all.keys) {
                                    if (key.startsWith("modified/")) edit.remove(key)
                                }
                            }
                            check(edit.commit()) { "Could not store GitHub session." }
                            SyncAuthGuard.clearAfterLogin(config)
                        }
                        SyncTrace.info("github-auth-step", "step=session-commit event=success")
                        authStep = "first-sync"
                        val progress = showSyncProgress()
                        try {
                            val report = withContext(Dispatchers.IO) {
                                SyncCoordinator.synchronize(host.applicationContext, host)
                            }
                            if (finishSyncProgress(progress, report, firstConnection)) {
                                if (firstConnection && restartCloudStreamAfterFirstSignIn(host)) {
                                    return@launch
                                }
                                progress.dialog.dismiss()
                                if (dialog.isShowing) dialog.dismiss()
                                if (isAdded) dismissAllowingStateLoss()
                            } else {
                                feedback.text = "Connection established, but initial sync is incomplete. " +
                                    "Do not use automatic sync until the error is resolved."
                            }
                        } finally {
                            if (progress.dialog.isShowing) progress.dialog.dismiss()
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        SyncTrace.info("github-auth-failed",
                            "step=$authStep type=TimeoutCancellationException http=0")
                        if (dialog.isShowing) feedback.text = "GitHub device authorization expired."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val code = GithubAuthFlow.httpCode(error)
                        SyncTrace.info("github-auth-failed",
                            "step=$authStep type=${error.javaClass.simpleName} http=$code")
                        if (code == 401 &&
                            authStep in setOf("account-profile", "backup-verification")) {
                            withContext(Dispatchers.IO) { GithubAuthFlow.clear(config) }
                            SyncTrace.info("github-auth-pending", "event=discarded http=401")
                        }
                        if (dialog.isShowing) feedback.text = "GitHub setup failed: " +
                            (error.message ?: error.javaClass.simpleName).take(220)
                    } finally {
                        if (dialog.isShowing) button.isEnabled = true
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            githubLoginJob?.cancel()
            githubLoginJob = null
            if (accountDialog === dialog) accountDialog = null
        }
        accountDialog = dialog
        dialog.show()
    }

    override fun onDestroy() {
        accountDialog?.dismiss()
        accountDialog = null
        statusLabel = null
        super.onDestroy()
    }
}
