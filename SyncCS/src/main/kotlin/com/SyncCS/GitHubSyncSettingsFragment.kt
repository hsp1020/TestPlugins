package com.SyncCS

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * GitHub-only, explicit-action recovery UI.
 * Legacy Supabase auto-sync is intentionally not started by this plugin version.
 */
class GitHubSyncSettingsFragment : DialogFragment() {
    private var status: TextView? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun vertical(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    private fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun field(context: Context, hint: String, initial: String = "", secret: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSingleLine(true)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun row(context: Context, parent: LinearLayout, text: String, action: () -> Unit) {
        val b = Button(context).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(b)
    }
    override fun onCreateDialog(savedInstanceState: android.os.Bundle?): Dialog {
        val context = requireContext()
        val config = GitHubSyncConfig(context)
        val root = vertical(context)
        status = label(context,
            "Private GitHub repository only. Existing Supabase records will not be imported.\n" +
                "Automatic sync and automatic plugin uninstall are disabled in this safety build.\n" +
                if (config.connected()) "Connected repository: " + config.repository else "Not connected")
        root.addView(status)

        row(context, root, "Connect using a fine-grained GitHub token") { connectToken() }
        row(context, root, "Sign in using GitHub App device code") { connectDeviceFlow() }
        row(context, root, "Inspect backup (no changes)") {
            operate("Inspect GitHub backup") {
                val preview = GitHubSyncEngine.inspect(requireContext())
                if (preview == null) "No GitHub backup exists yet"
                else "Complete backup: " + preview.count + " settings, " +
                    preview.pluginCount + " extensions. Generation: " + preview.generation
            }
        }
        row(context, root, "Create FIRST backup (empty backup branch only)") {
            confirm("Create first backup?",
                "Only use this on the authoritative source device after checking its local data. " +
                    "If a GitHub backup exists, creation is refused. Supabase is not accessed.") {
                GitHubSyncEngine.createFirstBackup(requireContext())
            }
        }
        row(context, root, "Restore GitHub backup to this device") {
            confirm("Replace local settings?",
                "CloudStream profiles and backed-up preferences will be restored. " +
                    "An encrypted local rollback checkpoint is saved FIRST. " +
                    "Installed plugin binaries are not modified in this safety build.") {
                GitHubSyncEngine.restoreExisting(requireContext())
            }
        }
        row(context, root, "Sync changed settings now") {
            confirm("Synchronize settings?",
                "Only run after a successful initial backup or restore. " +
                    "Mass deletions and unknown backup generations are blocked.") {
                GitHubSyncEngine.syncExisting(requireContext())
            }
        }
        row(context, root, "Disconnect this device") {
            AlertDialog.Builder(context).setTitle("Disconnect GitHub backup?")
                .setMessage("This discards the stored GitHub credential. It does not delete the remote backup.")
                .setPositiveButton("Disconnect") { _, _ ->
                    config.disconnect()
                    status?.text = "Disconnected. Existing local recovery checkpoints remain."
                }.setNegativeButton("Cancel", null).show()
        }
        val scroll = ScrollView(context).apply { addView(root) }
        return AlertDialog.Builder(context).setTitle("SyncCS · GitHub")
            .setView(scroll).setPositiveButton("Close", null).create()
    }

    private fun credentialForm(
        app: Boolean,
        submit: (repo: String, credential: String, passphrase: String, device: String) -> Unit
    ) {
        val context = requireContext()
        val cfg = GitHubSyncConfig(context)
        val view = vertical(context)
        view.addView(label(context,
            "Use a dedicated PRIVATE repository initialized with a README.\n" +
                if (app) "A GitHub App with Contents read/write and Device Flow enabled is required."
                else "Generate a fine-grained token limited to this backup repository (Contents: read/write)."))
        val repo = field(context, "owner/private-repo", cfg.repository)
        val credential = field(context, if (app) "GitHub App client ID" else "GitHub access token",
            "", !app)
        val passphrase = field(context, "Recovery passphrase (12+ characters)", "", true)
        val device = field(context, "Device name", cfg.deviceName)
        listOf(repo, credential, passphrase, device).forEach { view.addView(it) }
        AlertDialog.Builder(context)
            .setTitle(if (app) "GitHub device authorization" else "Private GitHub repository")
            .setView(ScrollView(context).apply { addView(view) })
            .setPositiveButton("Continue") { _, _ ->
                val name = repo.text.toString().trim()
                val input = credential.text.toString().trim()
                val password = passphrase.text.toString()
                val deviceName = device.text.toString().trim()
                if (input.isBlank() || password.length < 12 || deviceName.isBlank()) {
                    status?.text = "Connection rejected: enter a credential, device name and 12+ character passphrase."
                } else submit(name, input, password, deviceName)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun connectToken() {
        credentialForm(false) { repository, token, passphrase, device ->
            operate("Checking private GitHub repository") {
                val cfg = GitHubSyncConfig(requireContext())
                cfg.repository = repository
                cfg.deviceName = device
                cfg.storeCredentials(token, passphrase)
                GitHubSyncTransport(cfg).checkPrivateRepository()
                "GitHub connected: " + repository + ". Choose Inspect, Create or Restore next."
            }
        }
    }
    private fun connectDeviceFlow() {
        credentialForm(true) { repository, clientId, passphrase, device ->
            val ctx = requireContext()
            val cfg = GitHubSyncConfig(ctx)
            lifecycleScope.launch {
                try {
                    val challenge = withContext(Dispatchers.IO) { GitHubDeviceFlow.begin(clientId) }
                    val browser = Intent(Intent.ACTION_VIEW, Uri.parse(challenge.url))
                    startActivity(browser)
                    val dialog = AlertDialog.Builder(ctx).setTitle("Authorize SyncCS in GitHub")
                        .setMessage("Enter code: " + challenge.code + "\n\n" + challenge.url +
                            "\n\nThis may require installing the GitHub App on the private repository.")
                        .setNegativeButton("Cancel", null).create()
                    dialog.show()
                    var interval = challenge.interval
                    var remaining = 900L
                    var access: String? = null
                    while (remaining > 0 && dialog.isShowing && access == null) {
                        delay(interval * 1000)
                        remaining -= interval
                        val result = withContext(Dispatchers.IO) {
                            GitHubDeviceFlow.poll(clientId, challenge)
                        }
                        when (result.optString("error")) {
                            "" -> access = result.optString("access_token").takeIf { it.isNotBlank() }
                            "authorization_pending" -> Unit
                            "slow_down" -> interval = maxOf(interval + 5, result.optLong("interval", 5L))
                            else -> error("GitHub authorization: " + result.optString("error"))
                        }
                    }
                    dialog.dismiss()
                    val acceptedToken = access
                    if (acceptedToken != null) {
                        withContext(Dispatchers.IO) {
                            cfg.repository = repository
                            cfg.deviceName = device
                            cfg.storeCredentials(acceptedToken, passphrase)
                            GitHubSyncTransport(cfg).checkPrivateRepository()
                        }
                        status?.text = "GitHub authorized: " + repository +
                            ". Choose Inspect, Create or Restore next."
                    } else status?.text = "Authorization cancelled or expired"
                } catch (c: CancellationException) { throw c }
                catch (error: Exception) {
                    status?.text = "GitHub authorization failed: " + error.message.orEmpty().take(180)
                }
            }
        }
    }
    private fun confirm(title: String, message: String, action: suspend () -> String) {
        AlertDialog.Builder(requireContext()).setTitle(title).setMessage(message)
            .setPositiveButton("Continue") { _, _ -> operate(title, action) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun operate(title: String, action: suspend () -> String) {
        val context = requireContext()
        status?.text = title + "..."
        lifecycleScope.launch {
            val outcome = try { withContext(Dispatchers.IO) { action() } }
                catch (c: CancellationException) { throw c }
                catch (error: Exception) { "Failed: " + error.message.orEmpty().take(220) }
            status?.text = outcome
            if (isAdded) AlertDialog.Builder(context).setTitle("SyncCS")
                .setMessage(outcome).setPositiveButton("OK", null).show()
        }
    }
}
