package com.SyncCS

import android.app.Dialog
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
            "Review pending deletions",
            "Explicit approval required; automated deletions are blocked"
        ) {
            val request = SyncDeletionGate.pending(config)
            if (request.isEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("No pending deletions")
                    .setMessage("Automatic synchronization cannot remove stored settings, " +
                        "profiles or extensions without an explicit approval.")
                    .setPositiveButton("Close", null).show()
            } else {
                val details = request.take(25).joinToString("\n") { "• " + it }
                AlertDialog.Builder(context)
                    .setTitle("Confirm " + request.size + " removals?")
                    .setMessage("These entries were reported missing on this device. " +
                        "This may be a temporary loading error; only proceed if you " +
                        "personally removed them.\n\n" + details)
                    .setPositiveButton("Approve and sync") { _, _ ->
                        try {
                            SyncDeletionGate.authorizePending(config)
                            val host = requireActivity()
                            lifecycleScope.launch {
                                val progress = showSyncProgress()
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        SyncCoordinator.synchronize(host.applicationContext, host)
                                    }
                                    finishSyncProgress(progress, result)
                                } finally {
                                    if (progress.dialog.isShowing) progress.dialog.dismiss()
                                }
                            }
                        } catch (error: Exception) {
                            AlertDialog.Builder(context)
                                .setTitle("Removal approval failed")
                                .setMessage(error.message.orEmpty().take(220))
                                .setPositiveButton("Close", null).show()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })

        body.addView(settingRow(
            context,
            "Restore previous GitHub backup",
            "Browse encrypted Git revisions and restore a selected snapshot"
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
                    config.clearAccount()
                    statusLabel?.text = statusText(config)
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
        val status = if (config.active()) SyncCoordinator.lastStatus else "Not connected"
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

    private fun showBackupHistory() {
        val host = requireActivity()
        val config = SyncConfiguration(host)
        if (!config.active()) {
            AlertDialog.Builder(host).setTitle("Sign in required")
                .setMessage("Connect your GitHub account first.")
                .setPositiveButton("Close", null).show()
            return
        }
        lifecycleScope.launch {
            val progress = showSyncProgress()
            try {
                val previews = withContext(Dispatchers.IO) {
                    val token = config.preferences.getString("github_token", null)
                        ?: error("Missing GitHub access token")
                    val secret = String(LocalVault.decrypt(token), Charsets.UTF_8)
                    val store = GithubBackupStore(secret,
                        config.preferences.getString("github_login", null) ?: error("Missing GitHub login"),
                        deviceName = config.deviceName, deviceId = config.deviceId)
                    val now = store.current(SyncCipher.saved(config))
                        ?: error("No complete GitHub backup exists.")
                    store.history(now.branch, 30)
                        .filter { it.id != now.revision && it.summary.startsWith("SyncCS ") }
                }
                progress.dialog.dismiss()
                if (previews.isEmpty()) {
                    AlertDialog.Builder(host).setMessage("No older SyncCS backup revisions found.")
                        .setPositiveButton("Close", null).show()
                    return@launch
                }
                val labels = previews.map { it.timestamp + " · " + it.summary.take(70) }
                AlertDialog.Builder(host)
                    .setTitle("Choose backup revision")
                    .setItems(labels.toTypedArray()) { _, index ->
                        val selected = previews[index]
                        AlertDialog.Builder(host)
                            .setTitle("Restore this older snapshot?")
                            .setMessage("This creates a NEW Git commit preserving full history, " +
                                "increments recovery generation, and replaces synced local settings " +
                                "with the selected snapshot. Other devices must reconnect before uploading. " +
                                "Revision: " + selected.id.take(12))
                            .setPositiveButton("Restore") { _, _ ->
                                lifecycleScope.launch {
                                    val restoring = showSyncProgress()
                                    try {
                                        val restored = withContext(Dispatchers.IO) {
                                            check(config.preferences.edit()
                                                .putBoolean("github_recovery_in_progress", true)
                                                .commit())
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
                                                for (key in listOf(
                                                    "base", "remote_cursor", "remote_versions",
                                                    "profile_baseline", "profile_remote_snapshot",
                                                    "plugin_baseline", "plugin_remote_snapshot",
                                                    "pending_plugins", "list_baselines",
                                                    "pending_conflicts"
                                                )) editor.remove(key)
                                                for (key in config.preferences.all.keys) {
                                                    if (key.startsWith("modified/")) editor.remove(key)
                                                }
                                                check(editor.commit())
                                            } finally {
                                                config.preferences.edit()
                                                    .remove("github_recovery_in_progress").commit()
                                            }
                                            SyncCoordinator.synchronize(host.applicationContext, host)
                                        }
                                        finishSyncProgress(restoring, restored)
                                    } catch (error: Exception) {
                                        restoring.dialog.dismiss()
                                        AlertDialog.Builder(host)
                                            .setTitle("Backup recovery not completed")
                                            .setMessage(error.message.orEmpty().take(300))
                                            .setPositiveButton("Close", null).show()
                                    }
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Close", null).show()
            } catch (error: Exception) {
                progress.dialog.dismiss()
                AlertDialog.Builder(host).setTitle("Backup history unavailable")
                    .setMessage(error.message.orEmpty().take(300))
                    .setPositiveButton("Close", null).show()
            }
        }
    }

    private fun showAccountDialog() {
        if (accountDialog?.isShowing == true) return
        val context = requireContext()
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
        val dialog = AlertDialog.Builder(context)
            .setTitle("SyncCS · GitHub direct")
            .setView(scroll(context, body))
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
                    try {
                        val challenge = withContext(Dispatchers.IO) {
                            GithubDeviceCode.begin(client)
                        }
                        feedback.text = "Open " + challenge.verification +
                            " and enter this code: " + challenge.userCode +
                            "\nOn Android TV, enter the code from your phone or computer."
                        runCatching {
                            host.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(challenge.verification)))
                        }
                        var token: String? = null
                        var interval = challenge.pollSeconds
                        withTimeout(challenge.expiresSeconds * 1000L) {
                            while (token == null) {
                                delay(interval * 1000L)
                                try {
                                    token = withContext(Dispatchers.IO) {
                                        GithubDeviceCode.poll(client, challenge)
                                    }
                                } catch (slow: java.io.IOException) {
                                    if (slow.message?.contains("slower polling") == true) {
                                        interval += 5
                                    } else throw slow
                                }
                            }
                        }
                        val accessToken = token ?: error("GitHub authorization did not complete.")
                        val profile = withContext(Dispatchers.IO) {
                            GithubDeviceCode.profile(accessToken)
                        }
                        val previous = config.preferences.getString("github_account_id", null)
                        check(previous == null || previous == profile.id) {
                            "Different GitHub account: disconnect the current device first."
                        }
                        feedback.text = "Verifying private repository and recovery key..."
                        // Check repository privacy and passphrase BEFORE activating any sync.
                        val existingBackup = withContext(Dispatchers.IO) {
                            GithubBackupStore(accessToken, profile.login).current(
                                SyncCipher.derive(phrase, "https://api.github.com", profile.id))
                        }
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
                        withContext(Dispatchers.IO) {
                            SyncCipher.storeGithub(config, phrase, profile.id)
                            val edit = config.preferences.edit()
                                .putString("github_token", LocalVault.encrypt(
                                    accessToken.toByteArray(Charsets.UTF_8)))
                                .putString("github_account_id", profile.id)
                                .putString("github_login", profile.login)
                                .putString("github_client_id", client)
                                .putString("sync_transport", "github")
                                .putBoolean("github_initial_backup_confirmed", createFirst)
                                .putString("device_name", name)
                                .remove("account_switch_blocked")
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
                        }
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
                        if (dialog.isShowing) feedback.text = "GitHub device authorization expired."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
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
