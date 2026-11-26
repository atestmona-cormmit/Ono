package com.wolf.localmanager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipInputStream

// ---------------------------------------------------------------- model
data class Bot(
    val id: String,
    var name: String,
    var entry: String,
    var cred: String,
    var auto: Boolean,
    var hours: BooleanArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("entry", entry); put("cred", cred); put("auto", auto)
        put("hours", hours.joinToString("") { if (it) "1" else "0" })
    }

    companion object {
        fun fromJson(o: JSONObject): Bot {
            val h = o.optString("hours", "1".repeat(24))
            return Bot(
                o.getString("id"), o.getString("name"), o.optString("entry"), o.optString("cred"),
                o.optBoolean("auto", false), BooleanArray(24) { h.getOrNull(it) != '0' }
            )
        }
    }
}

object BotStore {
    private fun file(c: Context) = File(c.filesDir, "bots.json")
    fun dir(c: Context, id: String) = File(c.filesDir, "bots/$id")

    @Synchronized
    fun all(c: Context): List<Bot> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { Bot.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun saveAll(c: Context, l: List<Bot>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        file(c).writeText(a.toString())
    }

    @Synchronized
    fun add(c: Context, b: Bot) = saveAll(c, all(c) + b)

    @Synchronized
    fun remove(c: Context, id: String) = saveAll(c, all(c).filter { it.id != id })

    @Synchronized
    fun update(c: Context, id: String, f: (Bot) -> Unit) {
        val l = all(c)
        l.find { it.id == id }?.let { f(it); saveAll(c, l) }
    }
}

// ---------------------------------------------------------------- node
object NodeBridge {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("nodebridge")
    }
    external fun startNode(args: Array<String>, logPath: String, tmpDir: String, homeDir: String): Int
}

object NodeHost {
    const val PORT = 45731
    val token: String = UUID.randomUUID().toString()
    private var started = false

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (started) return
        started = true
        val c = ctx.applicationContext
        val dir = File(c.filesDir, "node").apply { mkdirs() }
        val script = File(dir, "supervisor.js")
        c.assets.open("supervisor.js").use { i -> script.outputStream().use { o -> i.copyTo(o) } }
        val tmp = File(c.cacheDir, "tmp").apply { mkdirs() }
        val log = File(dir, "node.log")
        Thread(null, {
            NodeBridge.startNode(
                arrayOf("node", script.absolutePath, PORT.toString(), token),
                log.absolutePath, tmp.absolutePath, c.filesDir.absolutePath
            )
        }, "node-main", 4L * 1024 * 1024).start()
    }
}

object NodeClient {
    private fun call(path: String, vararg p: Pair<String, String>): String {
        val qs = (p.toList() + ("t" to NodeHost.token))
            .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
        val con = URL("http://127.0.0.1:${NodeHost.PORT}$path?$qs").openConnection() as HttpURLConnection
        con.connectTimeout = 2000
        con.readTimeout = 8000
        return try { con.inputStream.bufferedReader().readText() } finally { con.disconnect() }
    }

    fun ready(): Boolean = try { call("/status"); true } catch (e: Exception) { false }

    fun statusSafe(): Map<String, JSONObject> = try {
        val o = JSONObject(call("/status")).getJSONObject("bots")
        val m = HashMap<String, JSONObject>()
        o.keys().forEach { m[it] = o.getJSONObject(it) }
        m
    } catch (e: Exception) { emptyMap() }

    fun start(id: String, dir: String, entry: String): String =
        JSONObject(call("/start", "id" to id, "dir" to dir, "entry" to entry)).optString("result", "?")

    fun stop(id: String): String = JSONObject(call("/stop", "id" to id)).optString("result", "?")

    fun logs(id: String, since: Int): Pair<List<String>, Int> {
        val o = JSONObject(call("/logs", "id" to id, "since" to since.toString()))
        val a = o.getJSONArray("lines")
        return (0 until a.length()).map { a.getString(it) } to o.getInt("next")
    }
}

// ---------------------------------------------------------------- files
object ZipUtil {
    fun unzip(input: InputStream, dest: File) {
        dest.mkdirs()
        val canon = dest.canonicalPath
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val out = File(dest, e.name.replace('\\', '/'))
                if (!out.canonicalPath.startsWith(canon + File.separator)) throw SecurityException("bad zip entry")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    fun listFiles(dir: File): List<String> {
        val out = ArrayList<String>()
        fun walk(d: File, depth: Int) {
            if (depth > 3) return
            d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.name == "node_modules" || f.name.startsWith(".")) return@forEach
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.extension.lowercase() in setOf("js", "mjs", "cjs", "json") && f.name != "package-lock.json")
                    out.add(f.relativeTo(dir).path)
            }
        }
        walk(dir, 0)
        return out.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
    }

    fun isCode(name: String) = name.substringAfterLast('.').lowercase() in setOf("js", "mjs", "cjs")

    fun guessEntry(dir: File, files: List<String>): String {
        try {
            val p = JSONObject(File(dir, "package.json").readText())
            val s = p.optJSONObject("scripts")?.optString("start", "") ?: ""
            Regex("""node\s+(\S+)""").find(s)?.groupValues?.get(1)?.removePrefix("./")?.let { if (files.contains(it)) return it }
            val m = p.optString("main", "").removePrefix("./")
            if (m.isNotEmpty() && files.contains(m)) return m
        } catch (e: Exception) { }
        val skip = Regex("x1|token|session|config|cred|loader", RegexOption.IGNORE_CASE)
        return files.firstOrNull { isCode(it) && !it.contains("/") && !skip.containsMatchIn(it) }
            ?: files.firstOrNull { isCode(it) } ?: ""
    }

    fun guessCred(files: List<String>, entry: String): String {
        val rx = Regex("x1|token|cred|secret|auth", RegexOption.IGNORE_CASE)
        return files.firstOrNull { it != entry && rx.containsMatchIn(it) } ?: ""
    }
}

// If the credentials file is plain JSON inside a .js file, wrap it so `import` / `require` works.
object CredUtil {
    fun wrap(dir: File, rel: String, text: String): String {
        val t = text.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return text
        val isModule: Boolean = when {
            rel.endsWith(".json") -> return text
            rel.endsWith(".mjs") -> true
            rel.endsWith(".cjs") -> false
            else -> try { JSONObject(File(dir, "package.json").readText()).optString("type") == "module" } catch (e: Exception) { false }
        }
        return (if (isModule) "export default " else "module.exports = ") + t + ";\n"
    }

    fun normalizeFile(dir: File, rel: String) {
        val f = File(dir, rel)
        if (!f.exists()) return
        val s = f.readText()
        val w = wrap(dir, rel, s)
        if (w != s) f.writeText(w)
    }
}

fun toast(ctx: Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
}
