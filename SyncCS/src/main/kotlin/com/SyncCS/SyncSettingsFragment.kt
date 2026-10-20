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

class SyncSettingsFragment : DialogFragment() {
    private var statusLabel: TextView? = null
    private var accountDialog: AlertDialog? = null
    private var githubLoginJob: Job? = null

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
            "Connect your GitHub account"
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
            statusLabel?.text = "Synchronizing..."
            lifecycleScope.launch {
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        SyncCoordinator.synchronize(host.applicationContext, host)
                    }
                    statusLabel?.text = outcome
                } catch (error: Exception) {
                    statusLabel?.text = "Sync failed: " +
                        (error.message ?: error.javaClass.simpleName).take(160)
                } finally {
                    row.isEnabled = true
                }
            }
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

    private fun showAccountDialog() {
        if (accountDialog?.isShowing == true) return
        val context = requireContext()
        val config = SyncConfiguration(context)
        val body = column(context)
        body.addView(secondary(
            context,
            "Sign in using your GitHub account. SyncCS does not need a separate email or password."
        ))
        val recovery = labeledField(
            context, body, "Recovery passphrase", "At least 12 characters",
            kind = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            revealToggle = true
        )
        body.addView(secondary(
            context,
            "This encrypts your synced data. Enter the same recovery passphrase on every device."
        ))
        val device = labeledField(
            context, body, "Device name", "Device name", config.deviceName, done = true
        )
        val feedback = secondary(context, "").apply {
            visibility = View.GONE
            setPadding(0, dp(context, 12), 0, 0)
        }
        body.addView(feedback)
        val dialog = AlertDialog.Builder(context)
            .setTitle("SyncCS · GitHub")
            .setView(scroll(context, body))
            .setPositiveButton("Sign in with GitHub", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            val signInButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            signInButton.setOnClickListener {
                if (githubLoginJob?.isActive == true) return@setOnClickListener
                val phrase = recovery.text.toString()
                val name = device.text.toString().trim().ifEmpty { Build.MODEL }
                if (phrase.length < 12) {
                    feedback.text = "Enter a recovery passphrase with at least 12 characters."
                    feedback.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                signInButton.isEnabled = false
                feedback.text = "Preparing GitHub login..."
                feedback.visibility = View.VISIBLE
                val host = requireActivity()
                githubLoginJob = lifecycleScope.launch {
                    try {
                        val attempt = withContext(Dispatchers.IO) {
                            SyncBackend(config).beginGithubLogin()
                        }
                        host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.browserUrl)))
                        feedback.text = "Complete GitHub login in your browser, then return to CloudStream. Waiting for confirmation..."
                        val authCode: String = withTimeout(4L * 60L * 1000L) {
                            var received: String? = null
                            while (received == null) {
                                delay(1600L)
                                received = withContext(Dispatchers.IO) {
                                    SyncBackend(config).pollGithubLogin(attempt.state)
                                }
                            }
                            received ?: throw java.io.IOException("Missing GitHub authorization code.")
                        }
                        feedback.text = "GitHub confirmed. Restoring SyncCS session..."
                        val report = withContext(Dispatchers.IO) {
                            SyncBackend(config).completeGithubLogin(
                                attempt.state, authCode, phrase
                            )
                            config.deviceName = name
                            SyncCoordinator.synchronize(host.applicationContext, host)
                        }
                        statusLabel?.text = report
                        if (report.startsWith("Sync failed:")) {
                            feedback.text = "Signed in, but synchronization failed: " + report.removePrefix("Sync failed: ")
                        } else {
                            if (dialog.isShowing) dialog.dismiss()
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        if (dialog.isShowing) feedback.text = "GitHub login timed out. Please try again."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (dialog.isShowing) {
                            feedback.text = "GitHub login failed: " +
                                (error.message ?: error.javaClass.simpleName).take(160)
                        }
                    } finally {
                        if (dialog.isShowing) signInButton.isEnabled = true
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
