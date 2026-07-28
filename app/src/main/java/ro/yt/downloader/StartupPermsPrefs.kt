package ro.yt.downloader

import android.content.Context

/** Reține dacă am cerut deja accesul „Toate fișierele” la prima pornire. */
class StartupPermsPrefs(context: Context) {
    private val p = context.getSharedPreferences("startup_perms", Context.MODE_PRIVATE)

    fun wasAllFilesPromptShown(): Boolean = p.getBoolean(KEY_ALL_FILES_SHOWN, false)

    fun markAllFilesPromptShown() {
        p.edit().putBoolean(KEY_ALL_FILES_SHOWN, true).apply()
    }

    fun wasRuntimePromptShown(): Boolean = p.getBoolean(KEY_RUNTIME_SHOWN, false)

    fun markRuntimePromptShown() {
        p.edit().putBoolean(KEY_RUNTIME_SHOWN, true).apply()
    }

    companion object {
        private const val KEY_ALL_FILES_SHOWN = "all_files_prompt_shown"
        private const val KEY_RUNTIME_SHOWN = "runtime_prompt_shown"
    }
}
