package com.phonepilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phonepilot.accessibility.ScreenObserver
import com.phonepilot.model.ScreenSnapshot
import com.phonepilot.service.PhonePilotAccessibilityService

class MainActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var statusDescription: TextView
    private lateinit var settingsButton: Button
    private lateinit var liveInfoContainer: LinearLayout
    private lateinit var livePackageText: TextView
    private lateinit var liveElementsText: TextView
    private lateinit var liveDetailsText: TextView

    private var unsubscribeScreenObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = createLayout()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()

        // Subscribe to live screen observation updates
        unsubscribeScreenObserver?.invoke()
        unsubscribeScreenObserver = PhonePilotAccessibilityService.screenObserver.addListener { snapshot ->
            runOnUiThread {
                updateLiveObservation(snapshot)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unsubscribeScreenObserver?.invoke()
        unsubscribeScreenObserver = null
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this) || PhonePilotAccessibilityService.isRunning

        if (isEnabled) {
            statusBadge.text = "● ACCESSIBILITY SERVICE ACTIVE"
            statusBadge.setTextColor(Color.parseColor("#4CAF50"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#1B5E20"), cornerRadius = 16f)

            statusDescription.text = "PhonePilot is actively observing UI events and ready for controller actions."
            settingsButton.text = "Accessibility Settings"
            liveInfoContainer.visibility = View.VISIBLE
        } else {
            statusBadge.text = "○ ACCESSIBILITY SERVICE NOT ENABLED"
            statusBadge.setTextColor(Color.parseColor("#FFA000"))
            statusBadge.background = createRoundedDrawable(Color.parseColor("#4E342E"), cornerRadius = 16f)

            statusDescription.text = "To enable Phase 1 UI observation and control, please grant PhonePilot accessibility permissions in Android Settings."
            settingsButton.text = "Enable in Accessibility Settings"
            liveInfoContainer.visibility = View.GONE
        }
    }

    private fun updateLiveObservation(snapshot: ScreenSnapshot) {
        livePackageText.text = "Active App: ${snapshot.packageName}"
        liveElementsText.text = "Detected Elements: ${snapshot.interactiveElements.size} interactive, ${snapshot.readableElements.size} readable"

        val preview = snapshot.interactiveElements.take(5).joinToString("\n") { el ->
            val desc = el.text ?: el.contentDescription ?: el.resourceId?.substringAfter(":id/") ?: el.className?.substringAfterLast('.')
            "• [$desc] at (${el.centerX}, ${el.centerY})"
        }
        liveDetailsText.text = if (preview.isNotEmpty()) "Sample interactive elements:\n$preview" else "No interactive elements in view"
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${PhonePilotAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expectedServiceName, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun createLayout(): View {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "PhonePilot"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(titleText)

        // Subtitle
        val subtitleText = TextView(this).apply {
            text = "Phase 1 — Android Controller"
            textSize = 15f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 8, 0, 48)
        }
        container.addView(subtitleText)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
        }

        statusBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 12, 24, 12)
        }
        statusCard.addView(statusBadge)

        statusDescription = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 24, 0, 32)
            setLineSpacing(6f, 1f)
        }
        statusCard.addView(statusDescription)

        settingsButton = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(Color.parseColor("#2979FF"), cornerRadius = 16f)
            setPadding(32, 24, 32, 24)
            setOnClickListener { openAccessibilitySettings() }
        }
        statusCard.addView(settingsButton)

        container.addView(statusCard)

        // Live observation Card
        liveInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), cornerRadius = 24f)
            setPadding(40, 40, 40, 40)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 36
            }
            layoutParams = params
            visibility = View.GONE
        }

        val liveTitle = TextView(this).apply {
            text = "Live Screen Observation"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        liveInfoContainer.addView(liveTitle)

        livePackageText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, 16, 0, 8)
        }
        liveInfoContainer.addView(livePackageText)

        liveElementsText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 16)
        }
        liveInfoContainer.addView(liveElementsText)

        liveDetailsText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#78909C"))
            setLineSpacing(4f, 1f)
        }
        liveInfoContainer.addView(liveDetailsText)

        container.addView(liveInfoContainer)

        scrollView.addView(container)
        return scrollView
    }

    private fun createRoundedDrawable(bgColor: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            setCornerRadius(cornerRadius)
        }
    }
}
