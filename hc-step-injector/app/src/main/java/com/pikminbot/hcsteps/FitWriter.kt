package com.pikminbot.hcsteps

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Google Fit pathway — writes step deltas to the Fit CLOUD via the Fitness
 * REST API, using the OWNED OAuth client (solid-century-261312, desktop app).
 *
 * Pikmin Bloom reads steps from Google Fit (local store), which syncs from
 * this cloud — so steps injected here reach the game after the Fit app syncs
 * down and the game is relaunched. This is the phone-agnostic path that the
 * proven fit_steps.py (host) uses; this class is the in-app equivalent so the
 * phone itself can inject without a host/GH cron.
 *
 * Token file: <filesDir>/fit_token.json (private, provisioned via ADB):
 *   {"refresh_token":"...","client_id":"...","client_secret":"...","token_uri":"https://oauth2.googleapis.com/token"}
 * Access tokens are refreshed in-app (desktop-client OAuth).
 */
object FitWriter {
    private const val TAG = "HCStepWriter"
    private const val FIT = "https://www.googleapis.com/fitness/v1/users/me"
    // Own dataSource, distinct from the GH cron's "pikmin_fit_inject", so this
    // app's PATCHes never overwrite the cron's ranges — the merged aggregate sums both.
    private const val DS_NAME = "pikmin_fit_app"
    private const val TOKEN_FILE = "fit_token.json"
    private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

    data class Token(
        val clientId: String,
        val clientSecret: String,
        val tokenUri: String,
        val refreshToken: String,
        var accessToken: String?,
        var expiresAt: Long
    )

    fun loadToken(ctx: Context): Token? = try {
        val f = File(ctx.filesDir, TOKEN_FILE)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            Token(
                o.optString("client_id"),
                o.optString("client_secret"),
                o.optString("token_uri", DEFAULT_TOKEN_URI),
                o.optString("refresh_token"),
                o.optString("access_token").ifEmpty { null },
                o.optLong("expires_at", 0)
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadToken failed", e)
        null
    }

    fun hasToken(ctx: Context): Boolean =
        loadToken(ctx)?.refreshToken?.isNotEmpty() == true

    private fun saveToken(ctx: Context, t: Token) {
        try {
            val o = JSONObject()
                .put("client_id", t.clientId)
                .put("client_secret", t.clientSecret)
                .put("token_uri", t.tokenUri)
                .put("refresh_token", t.refreshToken)
                .put("access_token", t.accessToken ?: "")
                .put("expires_at", t.expiresAt)
            File(ctx.filesDir, TOKEN_FILE).writeText(o.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveToken failed", e)
        }
    }

    private suspend fun refresh(t: Token): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("client_id", t.clientId)
                .put("client_secret", t.clientSecret)
                .put("refresh_token", t.refreshToken)
                .put("grant_type", "refresh_token")
            val conn = URL(t.tokenUri).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) {
                val o = JSONObject(resp)
                t.accessToken = o.getString("access_token")
                t.expiresAt = System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600) - 60
                t.accessToken
            } else {
                Log.e(TAG, "token refresh failed $code: $resp")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "token refresh err", e)
            null
        }
    }

    suspend fun getToken(ctx: Context): String? {
        val t = loadToken(ctx) ?: return null
        if (t.accessToken != null && t.expiresAt > System.currentTimeMillis() / 1000 + 60) {
            return t.accessToken
        }
        val fresh = refresh(t)
        if (fresh != null) saveToken(ctx, t)
        return fresh
    }

    private suspend fun api(ctx: Context, method: String, path: String, body: String? = null): JSONObject? {
        val tok = getToken(ctx) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(FIT + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code in 200..299) JSONObject(text)
                else JSONObject().put("error", code).put("msg", text.take(500))
            } catch (e: Exception) {
                Log.e(TAG, "api err $method $path", e)
                null
            }
        }
    }

    /** Create (or find) the raw step dataSource owned by this OAuth client. */
    private suspend fun ensureDatasource(ctx: Context): String? {
        val dsBody = JSONObject()
            .put("dataStreamName", DS_NAME)
            .put("type", "raw")
            .put("application", JSONObject().put("name", "pikmin-fit-inject"))
            .put("dataType", JSONObject()
                .put("name", "com.google.step_count.delta")
                .put("field", JSONArray().put(JSONObject().put("name", "steps").put("format", "integer"))))
        val r = api(ctx, "POST", "/dataSources", dsBody.toString())
        if (r != null && (r.has("dataSourceId") || r.has("dataStreamId"))) {
            return r.optString("dataSourceId").ifEmpty { r.getString("dataStreamId") }
        }
        if (r != null && r.optInt("error", 0) == 409) {
            val lst = api(ctx, "GET", "/dataSources")
            if (lst != null && lst.has("dataSource")) {
                val arr = lst.getJSONArray("dataSource")
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    if (d.optString("dataStreamName") == DS_NAME) {
                        return if (d.has("dataStreamId")) d.getString("dataStreamId")
                        else d.getString("dataSourceId")
                    }
                }
            }
        }
        Log.e(TAG, "ensureDatasource failed: $r")
        return null
    }

    private fun nano(ms: Long) = ms * 1_000_000L

    /**
     * Inject [count] steps distributed across the last [minutes], split into
     * [chunkMinutes]-long chunks (like the HC path). Returns a short result string.
     */
    suspend fun inject(ctx: Context, count: Int, minutes: Int, chunkMinutes: Int = 15): String {
        if (!hasToken(ctx)) return "ERR: no fit_token.json (provision via ADB)"
        val ds = ensureDatasource(ctx) ?: return "ERR: no dataSource (token expired?)"
        val dsEnc = ds.replace(":", "%3A").replace("/", "%2F")
        val nowMs = Instant.now().toEpochMilli()
        val startMs = nowMs - minutes * 60_000L
        val chunks = maxOf(1, (minutes + chunkMinutes - 1) / chunkMinutes)
        val base = count / chunks
        val rem = count - base * chunks
        var ok = true
        var done = 0
        val errs = StringBuilder()
        for (i in 0 until chunks) {
            val cStart = startMs + i * chunkMinutes * 60_000L
            val cEnd = minOf(cStart + chunkMinutes * 60_000L, nowMs)
            if (cEnd <= cStart) break
            val cCount = if (i == chunks - 1) base + rem else base
            val s = nano(cStart)
            val e = nano(cEnd)
            val body = JSONObject()
                .put("minStartTimeNs", s)
                .put("maxEndTimeNs", e)
                .put("dataSourceId", ds)
                .put("point", JSONArray().put(
                    JSONObject()
                        .put("startTimeNanos", s)
                        .put("endTimeNanos", e)
                        .put("dataTypeName", "com.google.step_count.delta")
                        .put("value", JSONArray().put(JSONObject().put("intVal", cCount)))
                ))
            val r = api(ctx, "PATCH", "/dataSources/$dsEnc/datasets/$s-$e", body.toString())
            if (r != null && !r.has("error")) {
                done += cCount
            } else {
                ok = false
                errs.append(if (r != null) r.optInt("error", -1) else "conn").append(',')
            }
        }
        return if (ok) "FIT OK: $done steps / $minutes min ($chunks chunks, ds=${ds.takeLast(18)})"
        else "FIT ERR: only $done/$count steps (errs=$errs)"
    }
}
