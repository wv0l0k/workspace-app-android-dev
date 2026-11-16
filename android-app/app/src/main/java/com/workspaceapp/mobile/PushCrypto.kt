package com.workspaceapp.mobile

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the FCM data payloads produced by `encrypt_push_payload()` in main.py.
 *
 * The server never hands Firebase a reminder's title, notes, or ids in the clear. It
 * derives a per-device AES-256 key from that device's login session token and sends
 * only an opaque `{"enc": "1", "payload": "..."}` envelope. The same session token is
 * mirrored here by WebAppInterface.setSessionToken, so this object can derive the
 * identical key offline and decrypt the notification locally. Google's FCM servers
 * therefore relay ciphertext, not content.
 *
 * The constants below are part of the wire format and must match the backend exactly:
 * HKDF-SHA256 with that salt/info, a 12-byte nonce, AES-GCM, and the salt again as
 * associated data. Changing any of them requires changing both sides and the version.
 */
object PushCrypto {

    const val VERSION = "1"

    private val SALT = "workspace-push-encryption-v1".toByteArray(Charsets.UTF_8)
    private val INFO = "fcm-data".toByteArray(Charsets.UTF_8)
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** True when this message's data map is the encrypted envelope. */
    fun isEncrypted(data: Map<String, String>): Boolean =
        data["enc"] == VERSION && !data["payload"].isNullOrEmpty()

    /**
     * HKDF-SHA256(session token) -> one 32-byte AES key. This is a direct transcription
     * of RFC 5869 for a single 32-byte output block:
     *
     *   PRK = HMAC-SHA256(salt, token)
     *   OKM = HMAC-SHA256(PRK, info || 0x01)
     */
    fun deriveKey(sessionToken: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT, "HmacSHA256"))
        val prk = mac.doFinal(sessionToken.toByteArray(Charsets.UTF_8))

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO)
        mac.update(0x01.toByte()) // T(1): first (and only) expand block
        return mac.doFinal().copyOf(KEY_BYTES)
    }

    /**
     * Returns the decrypted data map, or null if the payload is missing, corrupt, or
     * was encrypted with a different key (most likely a session that has since been
     * replaced). The caller decides what to show in that case.
     */
    fun decrypt(sessionToken: String, payloadBase64: String): Map<String, String>? {
        return try {
            val raw = Base64.decode(payloadBase64, Base64.DEFAULT)
            if (raw.size <= NONCE_BYTES) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = SecretKeySpec(deriveKey(sessionToken), "AES")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
            cipher.updateAAD(SALT)
            val plaintext = cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)

            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            val result = HashMap<String, String>()
            for (name in json.keys()) result[name] = json.optString(name)
            result
        } catch (e: Exception) {
            // Wrong key / tampered payload / malformed base64. Log without the content.
            android.util.Log.w("PushCrypto", "Could not decrypt push payload", e)
            null
        }
    }
}
