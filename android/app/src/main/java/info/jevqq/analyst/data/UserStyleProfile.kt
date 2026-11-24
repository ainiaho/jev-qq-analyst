package info.jevqq.analyst.data

import android.content.Context

internal enum class Tone { NEUTRAL, FORMAL, CASUAL }
internal enum class ReplyLength { ANY, SHORT, LONG }

/**
 * How the user talks, captured at onboarding and editable in settings.
 * Fed into the local reply suggester; never leaves the device as a profile
 * (only short derived hints may be prefixed into the analysis context).
 */
internal data class UserStyleProfile(
    val tone: Tone = Tone.NEUTRAL,
    val length: ReplyLength = ReplyLength.ANY,
    val catchphrase: String = "",
    val forbiddenWords: List<String> = emptyList(),
    val emojiFriendly: Boolean = true,
) {
    /** Bump when fields change; mixed into the verdict cache salt. */
    val version: String
        get() = "$tone|$length|${catchphrase.hashCode()}|${forbiddenWords.hashCode()}|$emojiFriendly"

    companion object {
        fun load(context: Context): UserStyleProfile {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return UserStyleProfile(
                tone = runCatching { Tone.valueOf(prefs.getString("tone", "NEUTRAL")!!) }
                    .getOrDefault(Tone.NEUTRAL),
                length = runCatching { ReplyLength.valueOf(prefs.getString("length", "ANY")!!) }
                    .getOrDefault(ReplyLength.ANY),
                catchphrase = prefs.getString("catchphrase", "").orEmpty(),
                forbiddenWords = prefs.getString("forbidden", "").orEmpty()
                    .split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                emojiFriendly = prefs.getBoolean("emoji_friendly", true),
            )
        }

        fun save(context: Context, profile: UserStyleProfile) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("tone", profile.tone.name)
                .putString("length", profile.length.name)
                .putString("catchphrase", profile.catchphrase)
                .putString("forbidden", profile.forbiddenWords.joinToString("\n"))
                .putBoolean("emoji_friendly", profile.emojiFriendly)
                .apply()
        }

        private const val FILE = "user_style"
    }
}
