package io.github.ninte86hl.hugminn

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** All of Hugminn's settings as one JSON file (to keep them over a reinstall or move them to another device). */
object Backup {
    /** Not saved: errors of this device and the feedback token of test builds. */
    private val skip = setOf("lastCrash", "crashSent", "githubToken")

    fun export(ctx: Context, uri: Uri): Boolean = runCatching {
        val p = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE)
        val prefs = JSONObject()
        p.all.forEach { (k, v) ->
            if (k in skip || k.startsWith("lastCrash")) return@forEach
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            prefs.put(k, e)
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val root = JSONObject().put("app", "Hugminn").put("format", 1).put("version", version).put("prefs", prefs)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) } ?: return false
        true
    }.getOrDefault(false)

    /** Number of restored settings, or -1 if the file is not a Hugminn backup. */
    fun import(ctx: Context, uri: Uri): Int = runCatching {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return -1
        val root = JSONObject(text)
        if (root.optString("app") != "Hugminn") return -1
        val prefs = root.getJSONObject("prefs")
        val ed = ctx.getSharedPreferences("hugminn", Context.MODE_PRIVATE).edit()
        var n = 0
        prefs.keys().forEach { k ->
            if (k in skip) return@forEach
            val e = prefs.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> return@forEach
            }
            n++
        }
        ed.commit()
        n
    }.getOrDefault(-1)
}
