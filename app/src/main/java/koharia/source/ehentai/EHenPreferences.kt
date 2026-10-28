package koharia.source.ehentai

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class EHenPreferences(private val store: PreferenceStore) {
    val site: Preference<EHenSite> = store.getEnum(KEY_SITE, EHenSite.E_HENTAI)
    val cookie: Preference<String> = store.getString(Preference.privateKey(KEY_COOKIE), "")
    val translateTags: Preference<Boolean> = store.getBoolean(KEY_TRANSLATE_TAGS, true)

    companion object {
        private const val KEY_SITE = "ehentai_site"
        private const val KEY_COOKIE = "ehentai_cookie"
        private const val KEY_TRANSLATE_TAGS = "ehentai_translate_tags"
    }
}

enum class EHenSite(val host: String, val apiHost: String) {
    E_HENTAI("e-hentai.org", "api.e-hentai.org"),
    EX_HENTAI("exhentai.org", "api.e-hentai.org"),
}
