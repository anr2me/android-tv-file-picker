package com.example.androidtvfilepicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

/** Start screen. It shows the permission state and has two test buttons. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var grant: Button
    private lateinit var adbHint: TextView
    private lateinit var testFile: Button
    private lateinit var rootToggle: Button
    private lateinit var crashTitle: TextView
    private lateinit var crashText: TextView
    private lateinit var crashClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EventLog.add(this, "Main onCreate saved=${savedInstanceState != null}")

        statusView = findViewById(R.id.status)
        resultView = findViewById(R.id.result)
        grant = findViewById(R.id.grant)
        adbHint = findViewById(R.id.adbHint)
        testFile = findViewById(R.id.testFile)
        rootToggle = findViewById(R.id.rootToggle)
        crashTitle = findViewById(R.id.crashTitle)
        crashText = findViewById(R.id.crashText)
        crashClear = findViewById(R.id.crashClear)
        crashClear.setOnClickListener {
            CrashLog.clear(this)
            refreshCrashReport()
        }

        adbHint.text = getString(R.string.permission_adb, packageName)
        grant.setOnClickListener { Storage.openAllFilesSettings(this) }
        rootToggle.setOnClickListener {
            if (RootAccess.isEnabled(this)) {
                RootAccess.setEnabled(this, false)
                refreshRootButton()
            } else {
                RootConsent.askEnable(this) { refreshRootButton() }
            }
        }

        testFile.setOnClickListener {
            startTest(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
            )
        }
        findViewById<Button>(R.id.logButton).setOnClickListener { showLog() }
        findViewById<Button>(R.id.sharesButton).setOnClickListener {
            startActivity(Intent(this, SharesActivity::class.java))
        }
        findViewById<Button>(R.id.testFolder).setOnClickListener {
            startTest(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.add(this, "Main onResume")
        val allowed = Storage.hasAllFilesAccess()
        statusView.setText(if (allowed) R.string.status_on else R.string.status_off)
        grant.visibility = if (allowed) View.GONE else View.VISIBLE
        adbHint.visibility = if (allowed) View.GONE else View.VISIBLE
        refreshRootButton()
        refreshCrashReport()
        showCrashDialogOnce()
        if (allowed) testFile.requestFocus() else grant.requestFocus()
    }

    override fun onDestroy() {
        EventLog.add(this, "Main onDestroy finishing=$isFinishing")
        super.onDestroy()
    }

    /** Shows the error of the last crash one time after the app starts. */
    private fun showCrashDialogOnce() {
        if (CrashLog.shown) return
        val report = CrashLog.read(this) ?: return
        CrashLog.shown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(report.lines().take(14).joinToString("\n"))
            .setPositiveButton(R.string.crash_clear) { _, _ ->
                CrashLog.clear(this)
                refreshCrashReport()
            }
            .setNegativeButton(R.string.crash_keep, null)
            .show()
    }

    /** Shows the diagnostic log. The user can scroll with the D-pad. */
    private fun showLog() {
        val body = TextView(this).apply {
            text = EventLog.read(this@MainActivity).ifEmpty { getString(R.string.log_empty) }
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setView(scroll)
            .setPositiveButton(R.string.log_close, null)
            .setNeutralButton(R.string.log_clear) { _, _ -> EventLog.clear(this) }
            .show()
        // Show the newest lines first.
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Shows the first lines of the last error, if the app closed after an error. */
    private fun refreshCrashReport() {
        val report = CrashLog.read(this)
        val visibility = if (report == null) View.GONE else View.VISIBLE
        crashTitle.visibility = visibility
        crashText.visibility = visibility
        crashClear.visibility = visibility
        if (report != null) crashText.text = report.lines().take(14).joinToString("\n")
    }

    private fun refreshRootButton() {
        rootToggle.setText(
            if (RootAccess.isEnabled(this)) R.string.root_toggle_on else R.string.root_toggle_off
        )
    }

    private fun startTest(intent: Intent) {
        // Set the package, so the test always uses this picker.
        intent.setPackage(packageName)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        resultView.text = ""
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_TEST)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TEST) return
        if (resultCode != RESULT_OK || data == null) {
            resultView.setText(R.string.test_cancelled)
            return
        }
        val uris = LinkedHashSet<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        resultView.text = getString(R.string.test_result, uris.joinToString("\n"))
    }

    companion object {
        private const val REQUEST_TEST = 1
    }
}
