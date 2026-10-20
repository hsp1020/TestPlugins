package com.SyncCS

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {
    private var label: TextView? = null

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + .5f).toInt()

    private fun field(context: Context, hint: String, value: String = "", secret: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            setSingleLine(true)
            setText(value)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }

    private fun container(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 16))
    }

    override fun onCreateView(inflater: LayoutInflater, root: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val config = SyncConfiguration(context)
        val content = container(context)
        content.addView(TextView(context).apply {
            text = "SyncCS – Supabase Sync"
            textSize = 19f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 12))
        })
        label = TextView(context).apply {
            text = SyncCoordinator.lastStatus + "\nDevice: " + config.deviceName
            setPadding(0, 0, 0, dp(context, 16))
            textSize = 14f
        }
        content.addView(label)
        val setup = Button(context).apply {
            text = "Account and server settings"
            setOnClickListener { showCredentials(context) }
        }
        content.addView(setup)
        content.addView(Button(context).apply {
            text = "Sync now"
            setOnClickListener {
                val activity = requireActivity()
                label?.text = "Synchronizing..."
                lifecycleScope.launch {
                    val report = withContext(Dispatchers.IO) {
                        SyncCoordinator.synchronize(activity.applicationContext, activity)
                    }
                    label?.text = report
                }
            }
        })
        content.addView(Button(context).apply {
            text = "Disconnect this device"
            setOnClickListener {
                AlertDialog.Builder(context).setTitle("Disconnect SyncCS")
                    .setMessage("Remove saved sync credentials on this device? Cloud data will be preserved.")
                    .setPositiveButton("Disconnect") { _, _ ->
                        config.clearAccount()
                        label?.text = "Disconnected"
                    }.setNegativeButton("Cancel", null).show()
            }
        })
        content.addView(TextView(context).apply {
            text = "Shared: library, watch progress, accounts, extensions, plugin settings, source priorities.\n" +
                "Device-specific: display, decoder, subtitle size, download paths.\n" +
                "The SyncCS cloud server is already configured. " +
                "Use the same account and recovery passphrase on each device."
            textSize = 13f
            setPadding(0, dp(context, 14), 0, 0)
        })
        return ScrollView(context).apply {
            addView(content)
        }
    }

    private fun showCredentials(context: Context) {
        val config = SyncConfiguration(context)
        val layout = container(context)
        val email = field(context, "Email", config.email)
        val password = field(context, "Supabase account password", secret = true)
        val phrase = field(context, "Recovery passphrase (12+ characters)", secret = true)
        val device = field(context, "Device name", config.deviceName)
        layout.addView(TextView(context).apply { text = "SyncCS Cloud · Seoul server (configured)" })
        listOf(email, password, phrase, device).forEach { layout.addView(it) }
        val dialog = AlertDialog.Builder(context)
            .setTitle("SyncCS account")
            .setView(ScrollView(context).apply { addView(layout) })
            .setPositiveButton("Sign in", null)
            .setNeutralButton("Sign up", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            fun runAuth(register: Boolean) {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                val recovery = phrase.text.toString()
                val name = device.text.toString().trim().ifEmpty { Build.MODEL }
                if (!mail.contains("@") || pass.isBlank() || recovery.length < 12) {
                    label?.text = "Enter email, account password and recovery passphrase (12+ characters)."
                    return
                }
                dialog.dismiss()
                val activity = requireActivity()
                label?.text = "Connecting..."
                lifecycleScope.launch {
                    val message = withContext(Dispatchers.IO) {
                        try {
                            config.deviceName = name
                            SyncBackend(config).signIn(mail, pass, recovery, register)
                            SyncCoordinator.synchronize(activity.applicationContext, activity)
                        } catch (error: Exception) {
                            "Connection failed: " + (error.message ?: error.javaClass.simpleName).take(160)
                        }
                    }
                    label?.text = message
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { runAuth(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { runAuth(true) }
        }
        dialog.show()
    }
}
