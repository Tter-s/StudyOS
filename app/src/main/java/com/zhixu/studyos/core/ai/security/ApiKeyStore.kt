package com.zhixu.studyos.core.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 存储接口。界面与规划服务只依赖该接口，便于测试替换。
 */
interface ApiKeyStore {
    suspend fun save(key: String)
    suspend fun load(): String?
    suspend fun clear()
    suspend fun hasKey(): Boolean
}

/**
 * 使用 Android Keystore 中的 AES-256-GCM 密钥加密 API Key。
 *
 * - 明文只存在于内存与加密后的 SharedPreferences 值中；
 * - 密钥由系统 Keystore 保护（不可导出），不写入 DataStore/数据库；
 * - `shared_prefs` 不在备份规则内，因此密钥不会进入系统备份或设备迁移；
 * - 解密失败（例如系统密钥被清除）时返回 null 并清理密文，要求用户重新填写；
 * - 任何路径都不写日志。
 */
class KeystoreApiKeyStore(context: Context) : ApiKeyStore {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val payload = "v1:" +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(KEY_CIPHERTEXT, payload).apply()
    }

    override suspend fun load(): String? {
        val payload = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val parts = payload.split(":")
            require(parts.size == 3 && parts[0] == "v1") { "不支持的密钥格式" }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrElse {
            // 密钥失效（Keystore 被清除/设备迁移）：清理密文，要求用户重新输入
            preferences.edit().remove(KEY_CIPHERTEXT).apply()
            null
        }
    }

    override suspend fun clear() {
        preferences.edit().remove(KEY_CIPHERTEXT).apply()
        // 不仅删密文，也删除 Keystore 别名，避免旧密文副本之后仍能被解密。
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    override suspend fun hasKey(): Boolean = preferences.contains(KEY_CIPHERTEXT)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "studyos_ai_secret"
        private const val KEY_CIPHERTEXT = "api_key_v1"
        private const val KEY_ALIAS = "studyos_ai_api_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * 密钥脱敏显示：只保留后四位，用于界面确认「已配置」而不会泄露完整密钥。
 */
object ApiKeyMasking {
    fun mask(key: String?): String {
        if (key.isNullOrBlank()) return "未配置"
        if (key.length <= 4) return "****"
        return "****" + key.takeLast(4)
    }
}
