package com.example.androidtvfilepicker

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The screen where the user adds, changes, tests, and deletes network shares. */
class SharesActivity : Activity() {

    private lateinit var adapter: ShareAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var addSmb: Button

    /** The orientation of the layout that is on screen. */
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = ShareAdapter { showEditor(it, it.type) }
        bindContent()
    }

    /** Inflates the layout for the orientation of the screen. Then connects the views. */
    private fun bindContent() {
        // If the layout is inflated again, the old list must release the adapter.
        if (::list.isInitialized) list.adapter = null
        setContentView(R.layout.activity_shares)
        orientation = resources.configuration.orientation

        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        addSmb = findViewById(R.id.addSmb)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        addSmb.setOnClickListener { showEditor(null, NetShare.Type.SMB) }
        findViewById<Button>(R.id.addWebdav).setOnClickListener {
            showEditor(null, NetShare.Type.WEBDAV)
        }
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
    }

    /**
     * The manifest handles the configuration changes of this screen, so Android does not
     * restart the screen when it turns. Inflate the layout again, to get the portrait or
     * the landscape layout.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != orientation) {
            EventLog.add(this, "Shares orientation changed: ${newConfig.orientation}")
            bindContent()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.add(this, "Shares onResume")
        refresh()
    }

    // ---- Diagnostic log ----
    // These events show how the screen ends: does the app close the screen (finish),
    // does the system destroy it, or does the process end without an event?

    override fun onStop() {
        EventLog.add(this, "Shares onStop")
        super.onStop()
    }

    override fun onDestroy() {
        EventLog.add(this, "Shares onDestroy finishing=$isFinishing configChange=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun finish() {
        // The first lines of the stack show who closes the screen.
        val caller = Log.getStackTraceString(Throwable()).lines().drop(2).take(5)
            .joinToString(" | ") { it.trim() }
        EventLog.add(this, "Shares finish() from $caller")
        super.finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        EventLog.add(this, "Shares window focus=$hasFocus")
    }

    private fun refresh() {
        val shares = Shares.list(this)
        adapter.submit(shares)
        emptyView.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
        if (shares.isEmpty()) {
            addSmb.requestFocus()
        } else {
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun showEditor(existing: NetShare?, type: NetShare.Type) {
        val view = layoutInflater.inflate(R.layout.dialog_share, null)
        val nameField = view.findViewById<EditText>(R.id.fieldName)
        val addressField = view.findViewById<EditText>(R.id.fieldAddress)
        val shareField = view.findViewById<EditText>(R.id.fieldShare)
        val folderField = view.findViewById<EditText>(R.id.fieldFolder)
        val userField = view.findViewById<EditText>(R.id.fieldUser)
        val passwordField = view.findViewById<EditText>(R.id.fieldPassword)
        val domainField = view.findViewById<EditText>(R.id.fieldDomain)
        val readOnlyCheck = view.findViewById<CheckBox>(R.id.checkReadOnly)
        val trustAllCheck = view.findViewById<CheckBox>(R.id.checkTrustAll)
        val httpWarning = view.findViewById<TextView>(R.id.httpWarning)
        val deleteButton = view.findViewById<Button>(R.id.buttonDelete)

        val smb = type == NetShare.Type.SMB
        val smbOnly = if (smb) View.VISIBLE else View.GONE
        shareField.visibility = smbOnly
        folderField.visibility = smbOnly
        domainField.visibility = smbOnly
        trustAllCheck.visibility = if (smb) View.GONE else View.VISIBLE

        addressField.setHint(if (smb) R.string.share_smb_address_hint else R.string.share_webdav_address_hint)
        passwordField.setHint(
            if (existing != null) R.string.share_password_keep_hint else R.string.share_password_hint
        )

        // The values of the last test. They are still here if the app closed during the test.
        val draft = Shares.loadDraft(this)?.takeIf {
            it.type == type &&
                if (existing != null) it.id == existing.id else Shares.get(this, it.id) == null
        }
        val source = draft ?: existing
        if (source != null) {
            nameField.setText(source.name)
            addressField.setText(source.address)
            shareField.setText(source.shareName)
            folderField.setText(source.basePath)
            userField.setText(source.username)
            domainField.setText(source.domain)
            readOnlyCheck.isChecked = source.readOnly
            trustAllCheck.isChecked = source.trustAllCerts
        } else {
            // A new share is read-only until the user changes this.
            readOnlyCheck.isChecked = true
        }
        if (draft != null) {
            if (draft.password.isNotEmpty()) passwordField.setText(draft.password)
            Toast.makeText(this, R.string.share_draft_restored, Toast.LENGTH_SHORT).show()
        }
        if (existing != null) deleteButton.visibility = View.VISIBLE
        // A new share keeps the same ID for each test and for the save.
        val formId = existing?.id ?: draft?.id ?: Shares.newId()

        fun updateWarning() {
            val http = !smb && addressField.text.toString().trim().startsWith("http://", ignoreCase = true)
            httpWarning.visibility = if (http) View.VISIBLE else View.GONE
        }
        updateWarning()
        addressField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateWarning()
        })

        /** Reads the form. Returns null and shows a message if a value is not valid. */
        fun build(): NetShare? {
            val address = addressField.text.toString().trim()
            if (address.isEmpty()) {
                toast(R.string.share_address_empty)
                return null
            }
            if (smb && shareField.text.toString().isBlank()) {
                toast(R.string.share_share_empty)
                return null
            }
            if (!smb && address.toHttpUrlOrNull() == null) {
                toast(R.string.share_url_invalid)
                return null
            }
            val password = passwordField.text.toString()
            val encrypted = try {
                if (password.isEmpty()) existing?.passwordEnc ?: "" else Secret.encrypt(password)
            } catch (e: Exception) {
                toast(R.string.share_encrypt_failed)
                return null
            }
            return NetShare(
                id = formId,
                type = type,
                name = nameField.text.toString().trim().ifEmpty { address },
                address = address,
                shareName = if (smb) shareField.text.toString().trim() else "",
                basePath = if (smb) folderField.text.toString().trim() else "",
                username = userField.text.toString().trim(),
                passwordEnc = encrypted,
                domain = if (smb) domainField.text.toString().trim() else "",
                trustAllCerts = !smb && trustAllCheck.isChecked,
                readOnly = readOnlyCheck.isChecked
            )
        }

        val title = when {
            existing != null -> R.string.share_edit_title
            smb -> R.string.share_new_smb_title
            else -> R.string.share_new_webdav_title
        }
        // The listeners of the buttons are set after the dialog shows. The dialog stays open then.
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.share_save, null)
            .setNeutralButton(R.string.share_test, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                build()?.let {
                    Shares.save(this, it)
                    dialog.dismiss()
                    refresh()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                // The times show if the Keystore or the storage blocks the main thread.
                val t0 = SystemClock.elapsedRealtime()
                val share = build()
                val t1 = SystemClock.elapsedRealtime()
                if (share != null) {
                    // Keep the values first. A failed test must not cost the user the input.
                    Shares.saveDraft(this, share)
                    val t2 = SystemClock.elapsedRealtime()
                    EventLog.add(this, "Test pressed: build ${t1 - t0} ms, draft ${t2 - t1} ms")
                    test(share)
                }
            }
        }

        // The user closed the form (save, cancel, or delete). The values are not necessary now.
        dialog.setOnDismissListener {
            EventLog.add(this, "Form dismissed")
            Shares.clearDraft(this)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                EventLog.add(this, "Form BACK key")
            }
            false
        }

        deleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.share_delete_confirm)
                .setPositiveButton(R.string.share_delete) { _, _ ->
                    existing?.let { Shares.delete(this, it.id) }
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        dialog.show()
    }

    private fun test(share: NetShare) {
        Toast.makeText(this, R.string.share_testing, Toast.LENGTH_SHORT).show()
        EventLog.add(this, "Test start type=${share.type}")
        Thread {
            val error = NetAccess.test(share)
            EventLog.add(this, "Test end: ${error ?: "ok"}")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (error == null) {
                    getString(R.string.share_test_ok)
                } else {
                    getString(R.string.share_test_failed, error)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** The list of shares. */
    private class ShareAdapter(
        private val onClick: (NetShare) -> Unit
    ) : RecyclerView.Adapter<ShareAdapter.Holder>() {

        private var items: List<NetShare> = emptyList()

        fun submit(shares: List<NetShare>) {
            items = shares
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val detail: TextView = view.findViewById(R.id.detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            val holder = Holder(view)
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val share = items[position]
            val context = holder.itemView.context
            holder.icon.setImageResource(R.drawable.ic_network)
            holder.name.text = share.name
            var detail = when (share.type) {
                NetShare.Type.SMB -> context.getString(R.string.share_detail_smb, share.address, share.shareName)
                NetShare.Type.WEBDAV -> context.getString(R.string.share_detail_webdav, share.address)
            }
            if (share.readOnly) detail += context.getString(R.string.share_read_only_suffix)
            holder.detail.text = detail
        }

        override fun getItemCount(): Int = items.size
    }
}
