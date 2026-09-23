package so.kontext.video.internal

import android.content.Context
import android.util.Log
import so.kontext.video.Constants
import java.util.UUID

internal fun log(message: String) {
    Log.d(Constants.LOG_TAG, message)
}

/** Persistent per-install id, generated once and kept in SharedPreferences. */
internal object InstallId {
    private const val PREFS = "so.kontext.video"
    private const val KEY = "install_id"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
