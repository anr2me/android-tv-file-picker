package com.example.androidtvfilepicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Start screen. It shows the permission state and has two test buttons. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var grant: Button
    private lateinit var adbHint: TextView
    private lateinit var testFile: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        resultView = findViewById(R.id.result)
        grant = findViewById(R.id.grant)
        adbHint = findViewById(R.id.adbHint)
        testFile = findViewById(R.id.testFile)

        adbHint.text = getString(R.string.permission_adb, packageName)
        grant.setOnClickListener { Storage.openAllFilesSettings(this) }

        testFile.setOnClickListener {
            startTest(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
            )
        }
        findViewById<Button>(R.id.testFolder).setOnClickListener {
            startTest(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
    }

    override fun onResume() {
        super.onResume()
        val allowed = Storage.hasAllFilesAccess()
        statusView.setText(if (allowed) R.string.status_on else R.string.status_off)
        grant.visibility = if (allowed) View.GONE else View.VISIBLE
        adbHint.visibility = if (allowed) View.GONE else View.VISIBLE
        if (allowed) testFile.requestFocus() else grant.requestFocus()
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
