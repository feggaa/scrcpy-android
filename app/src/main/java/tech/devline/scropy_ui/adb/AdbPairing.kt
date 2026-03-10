package tech.devline.scropy_ui.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.PrivateKey

/**
 * ADB Wireless Debugging Pairing (Android 11+).
 *
 * Uses native C++ (BoringSSL) for:
 *  - TLS 1.3 with mutual authentication (self-signed client cert)
 *  - SPAKE2 password-authenticated key exchange
 *  - AES-128-GCM encrypted PeerInfo exchange
 */
object AdbPairing {

    private const val TAG = "AdbPairing"

    init {
        System.loadLibrary("adb_pairing")
    }

    /**
     * Native JNI: returns null on success, or an error string on failure.
     */
    private external fun nativePair(
        host: String, port: Int, pairingCode: String,
        rsaPrivKeyDer: ByteArray, adbPubKey: ByteArray
    ): String?

    /**
     * Pair with a device using the 6-digit [pairingCode] shown on the
     * "Pair device with pairing code" screen, connecting to [host]:[pairPort].
     */
    suspend fun pair(
        host: String,
        pairPort: Int,
        pairingCode: String,
        privateKey: PrivateKey,
        publicKeyAdb: ByteArray,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Pairing with $host:$pairPort …")
        val error = nativePair(host, pairPort, pairingCode, privateKey.encoded, publicKeyAdb)
        if (error != null) {
            Log.e(TAG, "Pairing failed: $error")
            throw IOException(error)
        }
        Log.i(TAG, "Pairing succeeded!")
    }
}
