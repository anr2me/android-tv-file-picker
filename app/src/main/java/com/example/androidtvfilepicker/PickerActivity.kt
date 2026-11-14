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
            FileAdapter.Kind.ROOT, FileAdapter.Kind.NETWORK, FileAdapter.Kind.FOLDER ->
                openFolder(entry.file)
            FileAdapter.Kind.FILE -> {
                if (allowMultiple) {
                    adapter.toggle(entry)
                    updateActions()
                } else {
                    deliverChecked(listOf(entry.file), listOf(documentUri(entry.file)))
                }
            }
        }
    }

    private fun openFolder(dir: File) {
        if (RootAccess.isRestricted(this, dir) && !RootAccess.isEnabled(this)) {
            // Android protects this folder. Ask the user to turn on root support.
            RootConsent.askEnable(this) {
                currentDir = dir
                load()
            }
            return
        }
        currentDir = dir
        load()
    }

    private fun goUp(): Boolean {
        val dir = currentDir ?: return false
        pendingFocus = dir.absolutePath
        currentDir = if (Storage.isRoot(this, dir) || NetAccess.isShareRoot(dir)) null else dir.parentFile
        load()
        return true
    }

    private fun load() {
        val dir = currentDir
        val id = ++loadId
        pathView.text = when {
            dir == null -> getString(R.string.storage_roots)
            NetAccess.isVirtual(dir) -> NetAccess.displayPath(this, dir)
            else -> dir.absolutePath
        }

        Thread {
            var entries: List<FileAdapter.Entry> = emptyList()
            var error: Int? = null
            val roots = Storage.roots(this)

            if (dir == null) {
                entries = roots.map { FileAdapter.Entry(it.dir, it.title, FileAdapter.Kind.ROOT) } +
                    Shares.list(this).map {
                        FileAdapter.Entry(NetAccess.virtualFile(it.id), it.name, FileAdapter.Kind.NETWORK)
                    }
            } else if (NetAccess.isVirtual(dir)) {
                // A folder on a network share.
                val target = NetAccess.fromVirtual(dir)
                if (target == null) {
                    error = R.string.net_failed
                } else {
                    try {
                        entries = arrange(NetAccess.list(this, target.shareId, target.path).map {
                            FileAdapter.Entry(
                                File(dir, it.name),
                                it.name,
                                if (it.isDir) FileAdapter.Kind.FOLDER else FileAdapter.Kind.FILE,
                                it.size
                            )
                        })
                    } catch (e: IOException) {
                        error = R.string.net_failed
                    }
                }
            } else if (RootAccess.isRestricted(roots, dir)) {
                // Protected folder. Use root, if the user turned it on.
                if (RootAccess.isEnabled(this)) {
                    try {
                        entries = arrange(RootAccess.list(this, dir.absolutePath).map {
                            FileAdapter.Entry(
                                File(dir, it.name),
                                it.name,
                                if (it.isDir) FileAdapter.Kind.FOLDER else FileAdapter.Kind.FILE,
                                it.size
                            )
                        })
                    } catch (e: IOException) {
                        error = R.string.root_failed
                    }
                } else {
                    error = R.string.cannot_read_restricted
                }
            } else {
                val children = dir.listFiles()
                if (children == null) {
                    error = R.string.cannot_read
                } else {
                    entries = arrange(children.map {
                        // Android/data and Android/obb are folders, also if Android hides the details.
                        val isDir = it.isDirectory || RootAccess.isRestricted(roots, it)
                        FileAdapter.Entry(
                            it,
                            it.name,
                            if (isDir) FileAdapter.Kind.FOLDER else FileAdapter.Kind.FILE,
                            if (isDir) 0L else it.length()
                        )
                    })
                }
            }
            runOnUiThread {
                if (id == loadId && !isFinishing && !isDestroyed) show(entries, error)
            }
        }.start()
    }

    /** Applies the filters of the request and sorts the entries. */
    private fun arrange(items: List<FileAdapter.Entry>): List<FileAdapter.Entry> =
        items
            .filter {
                it.kind == FileAdapter.Kind.FOLDER ||
                    (mode == Mode.OPEN && Storage.mimeMatches(Storage.mimeOfName(it.name), mimeFilters))
            }
            .sortedWith(
                compareBy<FileAdapter.Entry>({ it.kind != FileAdapter.Kind.FOLDER }, { it.name.lowercase() })
            )

    private fun show(entries: List<FileAdapter.Entry>, error: Int?) {
        adapter.submit(entries)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyView.setText(error ?: R.string.empty_folder)
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
            Mode.OPEN -> {
                val files = adapter.selected.map { File(it) }
                deliverChecked(files, files.map { documentUri(it) })
            }
            Mode.TREE -> currentDir?.let { deliverChecked(listOf(it), listOf(treeUri(it))) }
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
        if (NetAccess.isVirtual(dir)) {
            createOnShare(dir, name)
            return
        }
        if (RootAccess.isRestricted(this, dir)) {
            RootConsent.askShare(this, callerLabel()) { createProtected(dir, name) }
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

    /** The document ID of a file. A file in a protected folder has the prefix "root:". */
    private fun documentId(file: File): String {
        // A file on a network share has the ID of the share and the path on the share.
        NetAccess.fromVirtual(file)?.let { return NetAccess.documentId(it.shareId, it.path) }
        return (if (RootAccess.isRestricted(this, file)) RootAccess.ID_PREFIX else "") + file.absolutePath
    }

    private fun documentUri(file: File): Uri =
        DocumentsContract.buildDocumentUri(Storage.authority(this), documentId(file))

    private fun treeUri(dir: File): Uri =
        DocumentsContract.buildTreeDocumentUri(Storage.authority(this), documentId(dir))

    /** Returns the URIs. If a file is in a protected folder, the user must allow it first. */
    private fun deliverChecked(files: List<File>, uris: List<Uri>) {
        if (files.none { RootAccess.isRestricted(this, it) }) {
            deliver(uris)
        } else {
            RootConsent.askShare(this, callerLabel()) { deliver(uris) }
        }
    }

    /** Creates a file on a network share. Then returns the URI. */
    private fun createOnShare(dir: File, name: String) {
        val target = NetAccess.fromVirtual(dir) ?: return
        if (Shares.get(this, target.shareId)?.readOnly == true) {
            Toast.makeText(this, R.string.net_read_only, Toast.LENGTH_LONG).show()
            return
        }
        primary.isEnabled = false
        Thread {
            try {
                val created = NetAccess.create(this, target.shareId, target.path, name, false)
                val id = NetAccess.documentId(target.shareId, NetAccess.child(target.path, created))
                val uri = DocumentsContract.buildDocumentUri(Storage.authority(this), id)
                runOnUiThread { deliver(listOf(uri)) }
            } catch (e: IOException) {
                runOnUiThread {
                    primary.isEnabled = true
                    Toast.makeText(this, R.string.create_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Creates a file in a protected folder with root. Then returns the URI. */
    private fun createProtected(dir: File, name: String) {
        primary.isEnabled = false
        Thread {
            try {
                val created = RootAccess.create(this, dir.absolutePath, name, false)
                val id = RootAccess.ID_PREFIX + dir.absolutePath + "/" + created
                val uri = DocumentsContract.buildDocumentUri(Storage.authority(this), id)
                runOnUiThread { deliver(listOf(uri)) }
            } catch (e: IOException) {
                runOnUiThread {
                    primary.isEnabled = true
                    Toast.makeText(this, R.string.create_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** The name of the app that started the picker. */
    private fun callerLabel(): String {
        val packageName = callingActivity?.packageName ?: return getString(R.string.caller_unknown)
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

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
        // The system picker makes each grant persistable, also if the app did not ask for it.
        // Many apps call takePersistableUriPermission without this flag in the request.
        // Without the flag, such a call throws a SecurityException and the app crashes.
        // The result of GET_CONTENT is not persistable.
        if (intent.action != Intent.ACTION_GET_CONTENT) {
            flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }

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
