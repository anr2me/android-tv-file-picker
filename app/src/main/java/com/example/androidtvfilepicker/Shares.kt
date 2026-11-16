package com.example.androidtvfilepicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** The data of one network share. */
data class NetShare(
    val id: String,
    val type: Type,
    val name: String,
    /** SMB: server name or IP address, with an optional port (host:port). WebDAV: the URL. */
    val address: String,
    /** SMB only: the name of the share. */
    val shareName: String,
    /** SMB only: a folder in the share, for example "Media/Movies". Empty for the root. */
    val basePath: String,
    val username: String,
    /** The password, encrypted with [Secret]. */
    val passwordEnc: String,
    /** SMB only. */
    val domain: String,
    /** WebDAV only: accept each TLS certificate. This is not safe. */
    val trustAllCerts: Boolean,
    val readOnly: Boolean
) {
    enum class Type { SMB, WEBDAV }

    /** The decrypted password. It is empty if the key is not available. */
    val password: String by lazy {
        try {
            Secret.decrypt(passwordEnc)
        } catch (e: Exception) {
            ""
        }
    }
}

/** Stores the list of network shares in the preferences of the app. */
object Shares {

    private const val PREFS = "settings"
    private const val KEY = "net_shares"

    @Volatile
    private var cache: List<NetShare>? = null

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    @Synchronized
    fun list(context: Context): List<NetShare> {
        cache?.let { return it }
        val result = ArrayList<NetShare>()
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (text != null) {
            try {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    fromJson(array.getJSONObject(i))?.let { result.add(it) }
                }
            } catch (e: JSONException) {
                // Use the shares that were read.
            }
        }
        cache = result
        return result
    }

    fun get(context: Context, id: String): NetShare? = list(context).firstOrNull { it.id == id }

    @Synchronized
    fun save(context: Context, share: NetShare) {
        val all = list(context).toMutableList()
        val index = all.indexOfFirst { it.id == share.id }
        if (index >= 0) all[index] = share else all.add(share)
        write(context, all)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
        NetClients.invalidate(id)
    }

    // ---- Draft ----
    // The editor keeps the values of its form when the user selects Test. If the app closes
    // during the test, the user does not need to type the values again.

    private const val DRAFT_KEY = "net_share_draft"

    fun saveDraft(context: Context, share: NetShare) {
        // commit() writes at once. apply() can lose the data if the app closes now.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DRAFT_KEY, toJson(share).toString()).commit()
    }

    fun loadDraft(context: Context): NetShare? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DRAFT_KEY, null) ?: return null
        return try {
            fromJson(JSONObject(text))
        } catch (e: JSONException) {
            null
        }
    }

    fun clearDraft(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DRAFT_KEY).apply()
    }

    private fun write(context: Context, shares: List<NetShare>) {
        val array = JSONArray()
        shares.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
        cache = shares
    }

    private fun toJson(share: NetShare): JSONObject = JSONObject().apply {
        put("id", share.id)
        put("type", share.type.name)
        put("name", share.name)
        put("address", share.address)
        put("share", share.shareName)
        put("path", share.basePath)
        put("user", share.username)
        put("pw", share.passwordEnc)
        put("domain", share.domain)
        put("trustAll", share.trustAllCerts)
        put("readOnly", share.readOnly)
    }

    private fun fromJson(json: JSONObject): NetShare? {
        val type = try {
            NetShare.Type.valueOf(json.getString("type"))
        } catch (e: Exception) {
            return null
        }
        val id = json.optString("id")
        if (id.isEmpty()) return null
        return NetShare(
            id = id,
            type = type,
            name = json.optString("name"),
            address = json.optString("address"),
            shareName = json.optString("share"),
            basePath = json.optString("path"),
            username = json.optString("user"),
            passwordEnc = json.optString("pw"),
            domain = json.optString("domain"),
            trustAllCerts = json.optBoolean("trustAll", false),
            readOnly = json.optBoolean("readOnly", true)
        )
    }
}
