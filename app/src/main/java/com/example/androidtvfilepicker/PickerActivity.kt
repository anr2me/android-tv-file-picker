package com.example.androidtvfilepicker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException

/**
 * The picker screen. It handles these intents:
 * OPEN_DOCUMENT, GET_CONTENT, CREATE_DOCUMENT, and OPEN_DOCUMENT_TREE.
 */
class PickerActivity : Activity() {

    private enum class Mode { OPEN, TREE, CREATE }

    private lateinit var mode: Mode
    private var allowMultiple = false
    private var mimeFilters: List<String> = listOf("*/*")

    /** The folder on screen. Null shows the list of storage volumes. */
    private var currentDir: File? = null
    private var loadId = 0
    private var loaded = false
    private var pendingFocus: String? = null

    private lateinit var adapter: FileAdapter
    private lateinit var content: View
    private lateinit var permissionPanel: View
    private lateinit var list: RecyclerView
    private lateinit var pathView: TextView
    private lateinit var emptyView: TextView
    private lateinit var fileName: EditText
    private lateinit var primary: Button
    private lateinit var cancel: Button
    private lateinit var grant: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        setResult(RESULT_CANCELED)
        readRequest()

        content = findViewById(R.id.content)
        permissionPanel = findViewById(R.id.permissionPanel)
        list = findViewById(R.id.list)
        pathView = findViewById(R.id.path)
        emptyView = findViewById(R.id.empty)
        fileName = findViewById(R.id.fileName)
        primary = findViewById(R.id.primary)
        cancel = findViewById(R.id.cancel)
        grant = findViewById(R.id.grant)

        findViewById<TextView>(R.id.title).setText(
            when (mode) {
                Mode.OPEN -> if (allowMultiple) R.string.title_open_files else R.string.title_open_file
                Mode.TREE -> R.string.title_open_folder
                Mode.CREATE -> R.string.title_save_file
            }
        )
        findViewById<TextView>(R.id.adbHint).text = getString(R.string.permission_adb, packageName)

        adapter = FileAdapter { onEntryClick(it) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        if (mode == Mode.CREATE) {
            fileName.visibility = View.VISIBLE
            fileName.setText(intent.getStringExtra(Intent.EXTRA_TITLE) ?: "")
        }

        primary.setOnClickListener { onPrimaryClick() }
        cancel.setOnClickListener { cancelAndFinish() }
        findViewById<Button>(R.id.permissionCancel).setOnClickListener { cancelAndFinish() }
        grant.setOnClickListener { Storage.openAllFilesSettings(this) }

        currentDir = savedInstanceState?.getString(KEY_DIR)?.let { File(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDir?.let { outState.putString(KEY_DIR, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        val allowed = Storage.hasAllFilesAccess()
        permissionPanel.visibility = if (allowed) View.GONE else View.VISIBLE
        content.visibility = if (allowed) View.VISIBLE else View.GONE
        if (!allowed) {
            loaded = false
            grant.requestFocus()
        } else if (!loaded) {
            loaded = true
            load()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (!goUp()) cancelAndFinish()
    }

    // ---- Request ----

    private fun readRequest() {
        mode = when (intent.action) {
            Intent.ACTION_OPEN_DOCUMENT_TREE -> Mode.TREE
            Intent.ACTION_CREATE_DOCUMENT -> Mode.CREATE
            else -> Mode.OPEN
        }
        allowMultiple = mode == Mode.OPEN &&
            intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        val extraTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        val type = intent.type
        mimeFilters = when {
            extraTypes != null && extraTypes.isNotEmpty() -> extraTypes.toList()
            !type.isNullOrEmpty() -> listOf(type)
            else -> listOf("*/*")
        }
    }

    // ---- Navigation ----

    private fun onEntryClick(entry: FileAdapter.Entry) {
        when (entry.kind) {
            FileAdapter.Kind.ROOT, FileAdapter.Kind.FOLDER -> {
                currentDir = entry.file
                load()
            }
            FileAdapter.Kind.FILE -> {
                if (allowMultiple) {
                    adapter.toggle(entry)
                    updateActions()
                } else {
                    deliver(listOf(documentUri(entry.file)))
                }
            }
        }
    }

    private fun goUp(): Boolean {
        val dir = currentDir ?: return false
        pendingFocus = dir.absolutePath
        currentDir = if (Storage.isRoot(this, dir)) null else dir.parentFile
        load()
        return true
    }

    private fun load() {
        val dir = currentDir
        val id = ++loadId
        pathView.text = dir?.absolutePath ?: getString(R.string.storage_roots)

        Thread {
            var entries: List<FileAdapter.Entry> = emptyList()
            var readable = true
            if (dir == null) {
                entries = Storage.roots(this).map {
                    FileAdapter.Entry(it.dir, it.title, FileAdapter.Kind.ROOT)
                }
            } else {
                val children = dir.listFiles()
                if (children == null) {
                    readable = false
                } else {
                    val showFiles = mode == Mode.OPEN
                    entries = children
                        .filter { it.isDirectory || showFiles }
                        .filter {
                            it.isDirectory || Storage.mimeMatches(Storage.mimeOf(it), mimeFilters)
                        }
                        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                        .map {
                            FileAdapter.Entry(
                                it,
                                it.name,
                                if (it.isDirectory) FileAdapter.Kind.FOLDER else FileAdapter.Kind.FILE,
                                if (it.isFile) it.length() else 0L
                            )
                        }
                }
            }
            runOnUiThread {
                if (id == loadId && !isFinishing && !isDestroyed) show(entries, readable)
            }
        }.start()
    }

    private fun show(entries: List<FileAdapter.Entry>, readable: Boolean) {
        adapter.submit(entries)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyView.setText(if (readable) R.string.empty_folder else R.string.cannot_read)
        updateActions()

        val target = pendingFocus?.let { adapter.indexOfPath(it) }?.takeIf { it >= 0 } ?: 0
        pendingFocus = null
        list.post {
            if (entries.isNotEmpty()) {
                list.scrollToPosition(target)
                list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            } else {
                val next = if (primary.visibility == View.VISIBLE && primary.isEnabled) primary else cancel
                next.requestFocus()
            }
        }
    }

    private fun updateActions() {
        when (mode) {
            Mode.OPEN -> {
                primary.visibility = if (allowMultiple) View.VISIBLE else View.GONE
                primary.isEnabled = adapter.selected.isNotEmpty()
                primary.text = getString(R.string.action_select_count, adapter.selected.size)
            }
            Mode.TREE -> {
                primary.visibility = View.VISIBLE
                primary.isEnabled = currentDir != null
                primary.setText(R.string.action_use_folder)
            }
            Mode.CREATE -> {
                primary.visibility = View.VISIBLE
                primary.isEnabled = currentDir != null
                primary.setText(R.string.action_save_here)
            }
        }
    }

    // ---- Result ----

    private fun onPrimaryClick() {
        when (mode) {
            Mode.OPEN -> deliver(adapter.selected.map { documentUri(File(it)) })
            Mode.TREE -> currentDir?.let { deliver(listOf(treeUri(it))) }
            Mode.CREATE -> createFile()
        }
    }

    private fun createFile() {
        val dir = currentDir ?: return
        val name = fileName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.file_name_empty, Toast.LENGTH_SHORT).show()
            fileName.requestFocus()
            return
        }
        val target = Storage.uniqueFile(dir, name)
        val created = try {
            target.createNewFile()
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!created) {
            Toast.makeText(this, R.string.create_failed, Toast.LENGTH_LONG).show()
            return
        }
        deliver(listOf(documentUri(target)))
    }

    private fun documentUri(file: File): Uri =
        DocumentsContract.buildDocumentUri(Storage.authority(this), file.absolutePath)

    private fun treeUri(dir: File): Uri =
        DocumentsContract.buildTreeDocumentUri(Storage.authority(this), dir.absolutePath)

    private fun deliver(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val requested = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        var flags = requested or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (mode != Mode.OPEN) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        val result = Intent()
        result.addFlags(flags)
        if (uris.size == 1) result.data = uris[0]
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        result.clipData = clip

        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val KEY_DIR = "dir"
    }
}
