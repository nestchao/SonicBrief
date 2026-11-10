package dev.sonicbrief.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.sonicbrief.app.data.gemini.DEFAULT_GEMINI_MODEL
import dev.sonicbrief.app.data.groq.DEFAULT_GROQ_MODEL
import dev.sonicbrief.app.domain.model.ProcessingProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val model: String = DEFAULT_GEMINI_MODEL,
    val transcriptionProvider: ProcessingProvider = ProcessingProvider.GEMINI,
    val groqModel: String = DEFAULT_GROQ_MODEL,
    val transcriptLanguage: String = "auto",
    val summaryLanguage: String = "zh-CN",
    val hasApiKey: Boolean = false,
    val apiKeyLast4: String? = null,
    val hasGroqKey: Boolean = false,
    val groqKeyLast4: String? = null,
    val cookiesImported: Boolean = false,
)

/** Encrypts small secrets with an Android Keystore AES-GCM key (the key never leaves the secure hardware/TEE). */
@Singleton
class SecretCipher @Inject constructor() {
    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String, alias: String = ALIAS): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String, alias: String = ALIAS): String? = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, data.copyOfRange(0, IV_BYTES))) }
        String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "sonicbrief_gemini_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    fun observe(): Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = observe().first()

    suspend fun apiKey(): String? =
        dataStore.data.first()[API_KEY]?.let(cipher::decrypt)?.takeIf { it.isNotBlank() }

    suspend fun saveApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit {
            if (trimmed.isEmpty()) {
                it.remove(API_KEY)
                it.remove(API_KEY_LAST4)
            } else {
                it[API_KEY] = cipher.encrypt(trimmed)
                it[API_KEY_LAST4] = trimmed.takeLast(4)
            }
        }
    }

    suspend fun groqApiKey(): String? =
        dataStore.data.first()[GROQ_API_KEY]?.let { cipher.decrypt(it, GROQ_ALIAS) }?.takeIf { it.isNotBlank() }

    suspend fun saveGroqApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit {
            if (trimmed.isEmpty()) {
                it.remove(GROQ_API_KEY)
                it.remove(GROQ_API_KEY_LAST4)
            } else {
                it[GROQ_API_KEY] = cipher.encrypt(trimmed, GROQ_ALIAS)
                it[GROQ_API_KEY_LAST4] = trimmed.takeLast(4)
            }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        dataStore.edit {
            it[MODEL] = next.model
            it[PROVIDER] = next.transcriptionProvider.name.lowercase()
            it[GROQ_MODEL] = next.groqModel
            it[TRANSCRIPT_LANGUAGE] = next.transcriptLanguage
            it[SUMMARY_LANGUAGE] = next.summaryLanguage
        }
    }

    suspend fun setCookiesImported(imported: Boolean) {
        dataStore.edit { it[COOKIES] = if (imported) "1" else "" }
    }

    private fun Preferences.toSettings() = AppSettings(
        model = this[MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL,
        transcriptionProvider = runCatching {
            ProcessingProvider.valueOf(this[PROVIDER]?.uppercase().orEmpty())
        }.getOrDefault(ProcessingProvider.GEMINI),
        groqModel = this[GROQ_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_GROQ_MODEL,
        transcriptLanguage = this[TRANSCRIPT_LANGUAGE] ?: "auto",
        summaryLanguage = this[SUMMARY_LANGUAGE] ?: "zh-CN",
        hasApiKey = this[API_KEY] != null,
        apiKeyLast4 = this[API_KEY_LAST4],
        hasGroqKey = this[GROQ_API_KEY] != null,
        groqKeyLast4 = this[GROQ_API_KEY_LAST4],
        cookiesImported = this[COOKIES] == "1",
    )

    private companion object {
        const val GROQ_ALIAS = "sonicbrief_groq_key"
        val MODEL = stringPreferencesKey("model")
        val PROVIDER = stringPreferencesKey("transcription_provider")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val TRANSCRIPT_LANGUAGE = stringPreferencesKey("transcript_language")
        val SUMMARY_LANGUAGE = stringPreferencesKey("summary_language")
        val API_KEY = stringPreferencesKey("gemini_api_key_enc")
        val API_KEY_LAST4 = stringPreferencesKey("gemini_api_key_last4")
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key_enc")
        val GROQ_API_KEY_LAST4 = stringPreferencesKey("groq_api_key_last4")
        val COOKIES = stringPreferencesKey("cookies_imported")
    }
}
