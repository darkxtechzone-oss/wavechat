package com.darkx.wave

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption without a server.
 *  - every install owns an EC (P-256) key pair; the private key never leaves the phone
 *    (it is wrapped with an AES key that lives in the Android Keystore)
 *  - the public key is published in the user's database record
 *  - each message: ephemeral EC key -> ECDH with the receiver's public key -> HMAC-SHA256 -> AES-256-GCM
 * The database only ever stores ciphertext.
 *
 * Blob layout: [version=1][ephLen][ephemeral public key X.509][iv 12][ciphertext+tag]
 */
object Crypto {
    private const val ALIAS = "wave_wrap_key"
    private val rnd = SecureRandom()

    @Volatile
    private var privCache: PrivateKey? = null

    private fun prefs() = WaveApp.ctx.getSharedPreferences("wave_keys", Context.MODE_PRIVATE)

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val ex = ks.getKey(ALIAS, null)
        if (ex is SecretKey) return ex
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    private fun newPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Returns this install's public key (Base64), creating the key pair on first use. */
    @Synchronized
    fun ensureKeys(): String {
        val pub = prefs().getString("pub", null)
        if (pub != null && prefs().getString("priv", null) != null) return pub
        return generate()
    }

    @Synchronized
    fun generate(): String {
        val kp = newPair()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = c.iv
        val ct = c.doFinal(kp.private.encoded)
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        prefs().edit().putString("priv", Base64.encodeToString(iv + ct, Base64.NO_WRAP)).putString("pub", pubB64).apply()
        privCache = null
        return pubB64
    }

    @Synchronized
    private fun privateKey(): PrivateKey {
        val c0 = privCache
        if (c0 != null) return c0
        val raw = Base64.decode(prefs().getString("priv", "") ?: "", Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
        val pk = c.doFinal(ct)
        val k = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pk))
        privCache = k
        return k
    }

    /** Short readable fingerprint of this device's public key (to compare with a friend). */
    fun fingerprint(): String {
        val pub = Base64.decode(ensureKeys(), Base64.NO_WRAP)
        val h = Hash.hex(Hash.sha256(pub)).uppercase().substring(0, 24)
        return h.chunked(4).joinToString(" ")
    }

    private fun derive(priv: PrivateKey, pub: PublicKey, eph: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val secret = ka.generateSecret()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("wave-chat-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(secret)
        mac.update(eph)
        return mac.doFinal()
    }

    fun encrypt(plain: ByteArray, peerPubB64: String): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(peerPubB64, Base64.NO_WRAP)))
        val eph = newPair()
        val ep = eph.public.encoded
        val key = derive(eph.private, peer, ep)
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        val out = ByteArray(2 + ep.size + 12 + ct.size)
        out[0] = 1
        out[1] = ep.size.toByte()
        System.arraycopy(ep, 0, out, 2, ep.size)
        System.arraycopy(iv, 0, out, 2 + ep.size, 12)
        System.arraycopy(ct, 0, out, 2 + ep.size + 12, ct.size)
        return out
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 20 || blob[0].toInt() != 1) throw IllegalArgumentException("bad blob")
        val el = blob[1].toInt() and 0xff
        val ep = blob.copyOfRange(2, 2 + el)
        val iv = blob.copyOfRange(2 + el, 2 + el + 12)
        val ct = blob.copyOfRange(2 + el + 12, blob.size)
        val eph = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ep))
        val key = derive(privateKey(), eph, ep)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }
}
