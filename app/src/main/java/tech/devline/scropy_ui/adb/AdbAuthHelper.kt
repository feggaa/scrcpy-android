package tech.devline.scropy_ui.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Manages the RSA key pair used for ADB authentication and encodes the
 * public key in the specific binary format the ADB daemon expects.
 */
object AdbAuthHelper {

    private const val TAG = "AdbAuthHelper"
    private const val PREF = "adb_keys"
    private const val K_PRIV = "priv"
    private const val K_PUB  = "pub_adb"
    private const val KEY_BITS = 2048
    private const val MOD_WORDS = 64    // KEY_BITS / 32
    private const val MOD_BYTES = 256   // KEY_BITS / 8

    /** Returns (privateKey, publicKeyInAdbFormat). Generates and persists on first call. */
    fun getOrCreateKeys(context: Context): Pair<PrivateKey, ByteArray> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(K_PRIV, null)
        val pubB64  = prefs.getString(K_PUB,  null)

        if (privB64 != null && pubB64 != null) {
            runCatching {
                val privBytes = Base64.decode(privB64, Base64.DEFAULT)
                val privKey   = KeyFactory.getInstance("RSA")
                    .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                val pubBytes  = Base64.decode(pubB64, Base64.DEFAULT)
                return Pair(privKey, pubBytes)
            }.onFailure { Log.w(TAG, "Stored keys invalid, regenerating", it) }
        }

        Log.d(TAG, "Generating new ADB RSA key pair …")
        val kp  = KeyPairGenerator.getInstance("RSA").also { it.initialize(KEY_BITS) }.generateKeyPair()
        val priv = kp.private
        val pub  = kp.public as RSAPublicKey
        val adbPub = encodePublicKey(pub, "rabi3@dev")

        prefs.edit()
            .putString(K_PRIV, Base64.encodeToString(priv.encoded, Base64.DEFAULT))
            .putString(K_PUB,  Base64.encodeToString(adbPub, Base64.DEFAULT))
            .apply()

        return Pair(priv, adbPub)
    }

    /**
     * Sign an ADB auth token.
     *
     * ADB's native code does `RSA_sign(NID_sha1, token, token_size, …)`,
     * which treats the 20-byte token AS IF it were a pre-computed SHA1 hash
     * and wraps it in a DigestInfo { SHA1 OID, token }.
     *
     * Java's "SHA1withRSA" would *hash* the token first (double-hashing),
     * producing a signature the device can never verify.  Instead we build
     * DigestInfo ourselves and sign with "NONEwithRSA" (raw PKCS#1 v1.5).
     */
    fun sign(privKey: PrivateKey, token: ByteArray): ByteArray {
        // ASN.1 DER prefix for DigestInfo with SHA-1 AlgorithmIdentifier
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = sha1DigestInfoPrefix + token
        return Signature.getInstance("NONEwithRSA").run {
            initSign(privKey); update(digestInfo); sign()
        }
    }

    // ───────────────────────────────────────────────────────────────────── //
    //  ADB RSA public key binary format                                     //
    //  struct { uint32 len; uint32 n0inv; uint32 n[64]; uint32 rr[64];     //
    //           uint32 exponent; } followed by base64 + " hostname\n"      //
    // ───────────────────────────────────────────────────────────────────── //

    private fun encodePublicKey(pub: RSAPublicKey, hostname: String): ByteArray {
        val bigN   = pub.modulus
        val nBytes = bigN.toFixedBytes(MOD_BYTES)       // big-endian, 256 bytes

        // n[] – 64 LE uint32 words, n[0] = least significant word
        val n = IntArray(MOD_WORDS) { i ->
            val base = MOD_BYTES - 4 - i * 4
            leWord(nBytes, base)
        }

        // n0inv = -n[0]^(-1) mod 2^32
        val n0inv = computeN0Inv(n[0].toLong() and 0xFFFFFFFFL).toInt()

        // rr[] = R^2 mod N  where R = 2^2048
        val bigRR   = BigInteger.TWO.pow(KEY_BITS * 2).mod(bigN)
        val rrBytes = bigRR.toFixedBytes(MOD_BYTES)
        val rr = IntArray(MOD_WORDS) { i ->
            val base = MOD_BYTES - 4 - i * 4
            leWord(rrBytes, base)
        }

        // Build binary key structure (little-endian)
        val structSize = 4 + 4 + MOD_WORDS * 4 + MOD_WORDS * 4 + 4   // = 524 bytes
        val buf = ByteBuffer.allocate(structSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MOD_WORDS)
        buf.putInt(n0inv)
        n.forEach  { buf.putInt(it) }
        rr.forEach { buf.putInt(it) }
        buf.putInt(65537)   // public exponent

        val b64  = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 $hostname\n".toByteArray(Charsets.UTF_8)
    }

    /** Read a 32-bit little-endian integer from big-endian byte array at [base]. */
    private fun leWord(bytes: ByteArray, base: Int): Int =
        (bytes[base + 3].toInt() and 0xFF) or
        ((bytes[base + 2].toInt() and 0xFF) shl 8) or
        ((bytes[base + 1].toInt() and 0xFF) shl 16) or
        ((bytes[base].toInt() and 0xFF) shl 24)

    /** n0inv = -(n^(-1) mod 2^32). Uses BigInteger for correctness. */
    private fun computeN0Inv(n: Long): Long {
        val nBig  = BigInteger.valueOf(n)
        val mod   = BigInteger.TWO.pow(32)
        val nInv  = nBig.modInverse(mod)               // n^-1 mod 2^32
        return mod.subtract(nInv).toLong() and 0xFFFFFFFFL
    }

    /** BigInteger → big-endian byte array of exactly [size] bytes (zero-padded / trimmed). */
    private fun BigInteger.toFixedBytes(size: Int): ByteArray {
        val raw = toByteArray()
        return ByteArray(size).also { out ->
            val srcOff = maxOf(0, raw.size - size)
            val dstOff = size - minOf(raw.size, size)
            raw.copyInto(out, dstOff, srcOff)
        }
    }
}
