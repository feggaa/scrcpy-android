/*
 * ADB Wireless Pairing + TLS Connection - Native JNI Implementation
 *
 * Pairing protocol:
 *   1. TLS 1.3 mutual auth (self-signed cert from RSA key)
 *   2. SPAKE2 key exchange (password = pairing_code + exported_keying_material)
 *   3. AES-128-GCM encrypted PeerInfo exchange
 *
 * TLS Connection protocol (STLS upgrade):
 *   1. Plain TCP CNXN exchange (with "tls" feature)
 *   2. STLS negotiation
 *   3. TLS 1.3 upgrade (client cert from same RSA key)
 *   4. AUTH/CNXN over TLS
 *
 * Reference: platform/packages/modules/adb → pairing_auth/ + pairing_connection/ + tls/
 */

#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <string.h>
#include <netdb.h>
#include <errno.h>

#include <algorithm>
#include <atomic>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/evp.h>
#include <openssl/rsa.h>
#include <openssl/rand.h>
#include <openssl/err.h>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/aead.h>
#include <openssl/bn.h>

#define TAG "AdbPairingNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Constants from ADB source ────────────────────────────────────────────────

// SPAKE2 role names (sizeof includes null terminator – matches ADB)
static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";

// TLS exported keying material label (sizeof includes null – matches ADB)
static constexpr char kExportedKeyLabel[] = "adb-label";
static constexpr size_t kExportedKeySize = 64;

// AES-128-GCM
static constexpr size_t kHkdfKeyLength = 16;

// PeerInfo
static constexpr uint32_t kMaxPeerInfoSize = 8192;

// Pairing packet types
enum PairingPacketType : uint8_t { SPAKE2_MSG = 0, PEER_INFO = 1 };

// PeerInfo types
enum PeerInfoType : uint8_t { ADB_RSA_PUB_KEY = 0, ADB_DEVICE_GUID = 1 };

#pragma pack(push, 1)
struct PeerInfo {
    uint8_t type;
    uint8_t data[kMaxPeerInfoSize - 1];
};
#pragma pack(pop)
static_assert(sizeof(PeerInfo) == kMaxPeerInfoSize, "PeerInfo size mismatch");


// ── ADB protocol constants ──────────────────────────────────────────────────
static constexpr uint32_t A_CNXN = 0x4E584E43;
static constexpr uint32_t A_AUTH = 0x48545541;
static constexpr uint32_t A_STLS = 0x534c5453;
static constexpr uint32_t ADB_VERSION  = 0x01000000;
static constexpr uint32_t ADB_MAX_DATA = 1 << 20;


// ── TLS connection handle management ────────────────────────────────────────
struct TlsHandle {
    int fd;
    SSL* ssl;
    SSL_CTX* ssl_ctx;
    std::mutex read_mu;
    std::mutex write_mu;
    bool stls_path;  // true = STLS upgrade (CNXN already sent), false = TLS-first
};

static std::mutex g_handles_mu;
static std::map<jlong, TlsHandle*> g_handles;
static std::atomic<jlong> g_next_handle{1};


// ── Plain socket I/O helpers ────────────────────────────────────────────────

static bool sock_read_fully(int fd, uint8_t* buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = recv(fd, buf + off, len - off, 0);
        if (n <= 0) return false;
        off += n;
    }
    return true;
}

static bool sock_write_fully(int fd, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(fd, data + off, len - off, 0);
        if (n <= 0) return false;
        off += n;
    }
    return true;
}


// ── ADB message helpers (plain TCP, little-endian) ──────────────────────────

static uint32_t le32(const uint8_t* p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void put_le32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF;
    p[3] = (v >> 24) & 0xFF;
}

struct AdbMsg {
    uint32_t cmd, arg0, arg1, data_len, checksum, magic;
    std::vector<uint8_t> data;
};

static bool read_adb_msg(int fd, AdbMsg& msg) {
    uint8_t hdr[24];
    if (!sock_read_fully(fd, hdr, 24)) return false;
    msg.cmd      = le32(hdr);
    msg.arg0     = le32(hdr + 4);
    msg.arg1     = le32(hdr + 8);
    msg.data_len = le32(hdr + 12);
    msg.checksum = le32(hdr + 16);
    msg.magic    = le32(hdr + 20);
    if (msg.data_len > 0) {
        msg.data.resize(msg.data_len);
        if (!sock_read_fully(fd, msg.data.data(), msg.data_len)) return false;
    } else {
        msg.data.clear();
    }
    return true;
}

static bool write_adb_msg(int fd, uint32_t cmd, uint32_t arg0, uint32_t arg1,
                           const uint8_t* data, size_t data_len) {
    uint32_t chk = 0;
    for (size_t i = 0; i < data_len; i++) chk += data[i];
    uint8_t hdr[24];
    put_le32(hdr,      cmd);
    put_le32(hdr + 4,  arg0);
    put_le32(hdr + 8,  arg1);
    put_le32(hdr + 12, (uint32_t)data_len);
    put_le32(hdr + 16, chk);
    put_le32(hdr + 20, cmd ^ 0xFFFFFFFF);
    if (!sock_write_fully(fd, hdr, 24)) return false;
    if (data_len > 0 && !sock_write_fully(fd, data, data_len)) return false;
    return true;
}


// ── Generate self-signed X509 cert from existing EVP_PKEY ───────────────────

static X509* generate_cert(EVP_PKEY* pkey) {
    X509* x = X509_new();
    ASN1_INTEGER_set(X509_get_serialNumber(x), 1);
    X509_gmtime_adj(X509_get_notBefore(x), 0);
    X509_gmtime_adj(X509_get_notAfter(x), 365L * 24 * 3600);
    X509_set_pubkey(x, pkey);
    X509_NAME* name = X509_get_subject_name(x);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               (const uint8_t*)"adb", -1, -1, 0);
    X509_set_issuer_name(x, name);
    X509_sign(x, pkey, EVP_sha256());
    return x;
}


// ── Parse Java RSA private key (PKCS8 DER) → EVP_PKEY ──────────────────────

static EVP_PKEY* parse_rsa_key_der(const uint8_t* der, size_t der_len) {
    CBS cbs;
    CBS_init(&cbs, der, der_len);
    EVP_PKEY* pkey = EVP_parse_private_key(&cbs);
    if (!pkey) {
        // Fallback: try d2i
        const uint8_t* p = der;
        pkey = d2i_AutoPrivateKey(nullptr, &p, (long)der_len);
    }
    return pkey;
}


// ── AES-128-GCM cipher (matches adb/pairing_auth/aes_128_gcm.cpp) ───────────

class Aes128Gcm {
public:
    Aes128Gcm(const uint8_t* key_material, size_t key_material_len)
        : enc_seq_(0), dec_seq_(0) {
        uint8_t key[kHkdfKeyLength];
        // info string WITHOUT null terminator (sizeof-1), matching ADB
        uint8_t info[] = "adb pairing_auth aes-128-gcm key";
        HKDF(key, sizeof(key), EVP_sha256(),
             key_material, key_material_len,
             nullptr, 0,
             info, sizeof(info) - 1);
        ctx_ = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(),
                                key, sizeof(key),
                                EVP_AEAD_DEFAULT_TAG_LENGTH);
    }

    ~Aes128Gcm() { if (ctx_) EVP_AEAD_CTX_free(ctx_); }

    bool ok() const { return ctx_ != nullptr; }

    std::vector<uint8_t> Encrypt(const uint8_t* in, size_t in_len) {
        const EVP_AEAD* aead = EVP_AEAD_CTX_aead(ctx_);
        size_t nonce_len = EVP_AEAD_nonce_length(aead);
        std::vector<uint8_t> nonce(nonce_len, 0);
        memcpy(nonce.data(), &enc_seq_, std::min(sizeof(enc_seq_), nonce_len));

        size_t max_out = in_len + EVP_AEAD_max_overhead(aead);
        std::vector<uint8_t> out(max_out);
        size_t out_len = 0;
        if (!EVP_AEAD_CTX_seal(ctx_, out.data(), &out_len, max_out,
                               nonce.data(), nonce_len,
                               in, in_len, nullptr, 0)) {
            return {};
        }
        ++enc_seq_;
        out.resize(out_len);
        return out;
    }

    std::vector<uint8_t> Decrypt(const uint8_t* in, size_t in_len) {
        const EVP_AEAD* aead = EVP_AEAD_CTX_aead(ctx_);
        size_t nonce_len = EVP_AEAD_nonce_length(aead);
        std::vector<uint8_t> nonce(nonce_len, 0);
        memcpy(nonce.data(), &dec_seq_, std::min(sizeof(dec_seq_), nonce_len));

        std::vector<uint8_t> out(in_len);  // decrypted ≤ encrypted
        size_t out_len = 0;
        if (!EVP_AEAD_CTX_open(ctx_, out.data(), &out_len, in_len,
                               nonce.data(), nonce_len,
                               in, in_len, nullptr, 0)) {
            return {};
        }
        ++dec_seq_;
        out.resize(out_len);
        return out;
    }

private:
    EVP_AEAD_CTX* ctx_ = nullptr;
    uint64_t enc_seq_;
    uint64_t dec_seq_;
};


// (generate_cert_and_key removed - now using generate_cert() + parse_rsa_key_der() above)


// ── TLS helpers ──────────────────────────────────────────────────────────────

static bool tls_read_fully(SSL* ssl, uint8_t* buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        int n = SSL_read(ssl, buf + off, (int)std::min(len - off, (size_t)INT_MAX));
        if (n <= 0) return false;
        off += n;
    }
    return true;
}

static bool tls_write_fully(SSL* ssl, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        int n = SSL_write(ssl, data + off, (int)std::min(len - off, (size_t)INT_MAX));
        if (n <= 0) return false;
        off += n;
    }
    return true;
}


// ── Pairing packet framing ──────────────────────────────────────────────────
//  Header: version(1) + type(1) + payload_size(4 BE)  = 6 bytes

static bool write_pairing_packet(SSL* ssl, uint8_t type,
                                  const uint8_t* payload, uint32_t payload_len) {
    uint8_t hdr[6];
    hdr[0] = 1;  // version
    hdr[1] = type;
    uint32_t nl = htonl(payload_len);
    memcpy(hdr + 2, &nl, 4);
    return tls_write_fully(ssl, hdr, 6) &&
           (payload_len == 0 || tls_write_fully(ssl, payload, payload_len));
}

static bool read_pairing_header(SSL* ssl, uint8_t* type, uint32_t* payload_len) {
    uint8_t hdr[6];
    if (!tls_read_fully(ssl, hdr, 6)) return false;
    // hdr[0] = version (checked loosely)
    *type = hdr[1];
    uint32_t nl;
    memcpy(&nl, hdr + 2, 4);
    *payload_len = ntohl(nl);
    return true;
}


// ── Main pairing function ───────────────────────────────────────────────────

static std::string do_pair(const char* host, int port,
                           const char* pairing_code, size_t code_len,
                           const uint8_t* rsa_priv_key_der, size_t rsa_priv_key_der_len,
                           const uint8_t* adb_pub_key, size_t adb_pub_key_len) {
    // 1. Parse RSA private key and generate self-signed cert
    EVP_PKEY* pkey = parse_rsa_key_der(rsa_priv_key_der, rsa_priv_key_der_len);
    if (!pkey) {
        return "Failed to parse RSA private key";
    }
    X509* cert = generate_cert(pkey);
    LOGI("Generated self-signed TLS certificate");

    // 2. TCP connect
    struct addrinfo hints = {}, *res = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
        X509_free(cert); EVP_PKEY_free(pkey);
        return std::string("DNS resolution failed for ") + host;
    }

    int fd = socket(res->ai_family, SOCK_STREAM, 0);
    if (fd < 0) {
        freeaddrinfo(res); X509_free(cert); EVP_PKEY_free(pkey);
        return "socket() failed";
    }

    if (connect(fd, res->ai_addr, res->ai_addrlen) < 0) {
        std::string err = std::string("connect() failed: ") + strerror(errno);
        freeaddrinfo(res); close(fd); X509_free(cert); EVP_PKEY_free(pkey);
        return err;
    }
    freeaddrinfo(res);
    LOGI("TCP connected to %s:%d", host, port);

    // 3. TLS 1.3 mutual auth
    SSL_CTX* ssl_ctx = SSL_CTX_new(TLS_method());
    SSL_CTX_set_min_proto_version(ssl_ctx, TLS1_3_VERSION);
    SSL_CTX_set_max_proto_version(ssl_ctx, TLS1_3_VERSION);

    // Present our client certificate
    SSL_CTX_use_certificate(ssl_ctx, cert);
    SSL_CTX_use_PrivateKey(ssl_ctx, pkey);

    // Accept any peer certificate (auth happens via SPAKE2)
    SSL_CTX_set_custom_verify(ssl_ctx, SSL_VERIFY_PEER,
        [](SSL*, uint8_t*) -> enum ssl_verify_result_t {
            return ssl_verify_ok;
        });

    SSL* ssl = SSL_new(ssl_ctx);
    SSL_set_fd(ssl, fd);
    SSL_set_connect_state(ssl);

    if (SSL_do_handshake(ssl) != 1) {
        char errbuf[256];
        ERR_error_string_n(ERR_get_error(), errbuf, sizeof(errbuf));
        std::string err = std::string("TLS handshake failed: ") + errbuf;
        LOGE("%s", err.c_str());
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return err;
    }
    LOGI("TLS 1.3 handshake succeeded");

    // 4. Export keying material
    uint8_t exported_key[kExportedKeySize];
    if (!SSL_export_keying_material(ssl, exported_key, kExportedKeySize,
            kExportedKeyLabel, sizeof(kExportedKeyLabel),
            nullptr, 0, false)) {
        LOGE("Failed to export keying material");
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to export TLS keying material";
    }
    LOGI("Exported %zu bytes of keying material", kExportedKeySize);

    // 5. Build SPAKE2 password = pairing_code + exported_keying_material
    std::vector<uint8_t> password(code_len + kExportedKeySize);
    memcpy(password.data(), pairing_code, code_len);
    memcpy(password.data() + code_len, exported_key, kExportedKeySize);

    // 6. SPAKE2 exchange
    SPAKE2_CTX* spake = SPAKE2_CTX_new(spake2_role_alice,
                                        kClientName, sizeof(kClientName),
                                        kServerName, sizeof(kServerName));
    if (!spake) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "SPAKE2_CTX_new failed";
    }

    uint8_t spake_msg[SPAKE2_MAX_MSG_SIZE];
    size_t spake_msg_len = 0;
    if (!SPAKE2_generate_msg(spake, spake_msg, &spake_msg_len,
                             sizeof(spake_msg),
                             password.data(), password.size())) {
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "SPAKE2_generate_msg failed";
    }
    LOGI("Generated SPAKE2 message (%zu bytes)", spake_msg_len);

    // Send our SPAKE2 msg
    if (!write_pairing_packet(ssl, SPAKE2_MSG, spake_msg, (uint32_t)spake_msg_len)) {
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to send SPAKE2 message";
    }

    // Read peer's SPAKE2 msg
    uint8_t peer_type = 0;
    uint32_t peer_len = 0;
    if (!read_pairing_header(ssl, &peer_type, &peer_len)) {
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to read peer SPAKE2 header";
    }
    if (peer_type != SPAKE2_MSG) {
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Expected SPAKE2_MSG, got different type";
    }

    std::vector<uint8_t> peer_spake(peer_len);
    if (!tls_read_fully(ssl, peer_spake.data(), peer_len)) {
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to read peer SPAKE2 message";
    }
    LOGI("Received peer SPAKE2 message (%u bytes)", peer_len);

    // Process peer's SPAKE2 msg → key material
    uint8_t key_material[SPAKE2_MAX_KEY_SIZE];
    size_t key_material_len = 0;
    if (!SPAKE2_process_msg(spake, key_material, &key_material_len,
                            sizeof(key_material),
                            peer_spake.data(), peer_spake.size())) {
        LOGE("SPAKE2_process_msg failed – wrong pairing code?");
        SPAKE2_CTX_free(spake);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "SPAKE2 verification failed (wrong pairing code?)";
    }
    SPAKE2_CTX_free(spake);
    LOGI("SPAKE2 key exchange succeeded (%zu bytes key material)", key_material_len);

    // 7. Initialize AES-128-GCM cipher
    Aes128Gcm cipher(key_material, key_material_len);
    if (!cipher.ok()) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to init AES-128-GCM cipher";
    }

    // 8. Build our PeerInfo
    PeerInfo our_info;
    memset(&our_info, 0, sizeof(our_info));
    our_info.type = ADB_RSA_PUB_KEY;
    size_t copy_len = std::min(adb_pub_key_len, sizeof(our_info.data));
    memcpy(our_info.data, adb_pub_key, copy_len);

    // Encrypt PeerInfo
    auto encrypted = cipher.Encrypt(reinterpret_cast<uint8_t*>(&our_info),
                                    sizeof(our_info));
    if (encrypted.empty()) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to encrypt PeerInfo";
    }

    // Send encrypted PeerInfo
    if (!write_pairing_packet(ssl, PEER_INFO,
                              encrypted.data(), (uint32_t)encrypted.size())) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to send PeerInfo";
    }
    LOGI("Sent encrypted PeerInfo (%zu bytes)", encrypted.size());

    // 9. Read peer's encrypted PeerInfo
    if (!read_pairing_header(ssl, &peer_type, &peer_len)) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to read peer PeerInfo header";
    }
    if (peer_type != PEER_INFO) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Expected PEER_INFO, got different type";
    }

    std::vector<uint8_t> peer_encrypted(peer_len);
    if (!tls_read_fully(ssl, peer_encrypted.data(), peer_len)) {
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to read peer encrypted PeerInfo";
    }

    auto peer_decrypted = cipher.Decrypt(peer_encrypted.data(),
                                         peer_encrypted.size());
    if (peer_decrypted.size() != sizeof(PeerInfo)) {
        LOGE("PeerInfo decrypt mismatch: got %zu, expected %zu",
             peer_decrypted.size(), sizeof(PeerInfo));
        SSL_free(ssl); SSL_CTX_free(ssl_ctx); close(fd);
        X509_free(cert); EVP_PKEY_free(pkey);
        return "Failed to decrypt peer PeerInfo (wrong pairing code?)";
    }

    LOGI("Pairing successful! Received peer info.");

    // Cleanup
    SSL_shutdown(ssl);
    SSL_free(ssl);
    SSL_CTX_free(ssl_ctx);
    close(fd);
    X509_free(cert);
    EVP_PKEY_free(pkey);

    return "";  // empty = success
}


// ── JNI: Pairing ────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_tech_devline_scropy_1ui_adb_AdbPairing_nativePair(
        JNIEnv* env, jobject /* thiz */,
        jstring j_host, jint port,
        jstring j_code,
        jbyteArray j_rsa_priv_key_der,
        jbyteArray j_adb_pub_key) {

    const char* host = env->GetStringUTFChars(j_host, nullptr);
    const char* code = env->GetStringUTFChars(j_code, nullptr);
    size_t code_len = strlen(code);

    jsize priv_len = env->GetArrayLength(j_rsa_priv_key_der);
    jbyte* priv_data = env->GetByteArrayElements(j_rsa_priv_key_der, nullptr);

    jsize key_len = env->GetArrayLength(j_adb_pub_key);
    jbyte* key_data = env->GetByteArrayElements(j_adb_pub_key, nullptr);

    std::string result = do_pair(host, port, code, code_len,
                                 reinterpret_cast<const uint8_t*>(priv_data),
                                 (size_t)priv_len,
                                 reinterpret_cast<const uint8_t*>(key_data),
                                 (size_t)key_len);

    env->ReleaseByteArrayElements(j_adb_pub_key, key_data, JNI_ABORT);
    env->ReleaseByteArrayElements(j_rsa_priv_key_der, priv_data, JNI_ABORT);
    env->ReleaseStringUTFChars(j_code, code);
    env->ReleaseStringUTFChars(j_host, host);

    if (result.empty()) {
        return nullptr;  // success
    }
    return env->NewStringUTF(result.c_str());
}


// ══════════════════════════════════════════════════════════════════════════════
//  TLS Connection for wireless debugging
//
//  Tries TLS-first, falls back to STLS upgrade if peer rejects immediate TLS.
//  The STLS path: CNXN(v=0x01000001) → [server CNXN] → [server STLS] →
//                 [client STLS] → TLS handshake → AUTH/CNXN over TLS
// ══════════════════════════════════════════════════════════════════════════════

// Version that signals TLS support (per ADB protocol)
static constexpr uint32_t ADB_VERSION_TLS = 0x01000001;

static SSL_CTX* create_tls_ctx(EVP_PKEY* pkey, X509* cert) {
    SSL_CTX* ctx = SSL_CTX_new(TLS_method());
    // Allow TLS 1.2 and 1.3
    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    SSL_CTX_set_max_proto_version(ctx, TLS1_3_VERSION);

    SSL_CTX_use_certificate(ctx, cert);
    SSL_CTX_use_PrivateKey(ctx, pkey);

    // Accept any server cert
    SSL_CTX_set_custom_verify(ctx, SSL_VERIFY_PEER,
        [](SSL*, uint8_t*) -> enum ssl_verify_result_t {
            return ssl_verify_ok;
        });

    return ctx;
}

static std::string do_tls_handshake(int fd, EVP_PKEY* pkey, X509* cert,
                                     SSL** out_ssl, SSL_CTX** out_ctx) {
    SSL_CTX* ssl_ctx = create_tls_ctx(pkey, cert);
    SSL* ssl = SSL_new(ssl_ctx);
    SSL_set_fd(ssl, fd);
    SSL_set_connect_state(ssl);

    int ret = SSL_do_handshake(ssl);
    if (ret != 1) {
        int ssl_err = SSL_get_error(ssl, ret);
        unsigned long err_code = ERR_get_error();
        char errbuf[256] = {0};
        if (err_code != 0) {
            ERR_error_string_n(err_code, errbuf, sizeof(errbuf));
        } else {
            snprintf(errbuf, sizeof(errbuf), "SSL_get_error=%d, errno=%d (%s)",
                     ssl_err, errno, strerror(errno));
        }
        LOGE("TLS handshake failed: %s", errbuf);
        SSL_free(ssl); SSL_CTX_free(ssl_ctx);
        return std::string("TLS handshake failed: ") + errbuf;
    }

    const char* version = SSL_get_version(ssl);
    LOGI("TLS handshake succeeded (version=%s)", version ? version : "?");

    *out_ssl = ssl;
    *out_ctx = ssl_ctx;
    return "";
}

static std::string do_tls_connect(const char* host, int port,
                                   const uint8_t* rsa_priv_key_der,
                                   size_t rsa_priv_key_der_len,
                                   jlong* out_handle) {
    // 1. Parse RSA private key
    EVP_PKEY* pkey = parse_rsa_key_der(rsa_priv_key_der, rsa_priv_key_der_len);
    if (!pkey) return "Failed to parse RSA private key";
    LOGI("Parsed RSA private key (type=%d)", EVP_PKEY_id(pkey));

    // 2. Generate self-signed cert (same key used during pairing)
    X509* cert = generate_cert(pkey);
    if (!cert) { EVP_PKEY_free(pkey); return "Failed to generate TLS certificate"; }
    LOGI("Generated self-signed cert for TLS connect");

    // 3. TCP connect
    struct addrinfo hints = {}, *res = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
        EVP_PKEY_free(pkey); X509_free(cert);
        return std::string("DNS resolution failed for ") + host;
    }

    int fd = socket(res->ai_family, SOCK_STREAM, 0);
    if (fd < 0) {
        freeaddrinfo(res); EVP_PKEY_free(pkey); X509_free(cert);
        return "socket() failed";
    }

    struct timeval tv;
    tv.tv_sec = 10; tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    if (connect(fd, res->ai_addr, res->ai_addrlen) < 0) {
        std::string err = std::string("TCP connect failed: ") + strerror(errno);
        freeaddrinfo(res); close(fd); EVP_PKEY_free(pkey); X509_free(cert);
        return err;
    }
    freeaddrinfo(res);
    LOGI("TCP connected to %s:%d", host, port);

    // 4. Try STLS upgrade path first (standard ADB wireless debugging protocol)
    //    Send CNXN with TLS-capable version, expect CNXN+STLS back
    LOGI("Attempting STLS upgrade path...");
    const char* banner = "host::features=stat_v2,ls_v2,apex,abb,fixed_push_symlink_timestamp";
    if (!write_adb_msg(fd, A_CNXN, ADB_VERSION_TLS, ADB_MAX_DATA,
                       (const uint8_t*)banner, strlen(banner))) {
        close(fd); EVP_PKEY_free(pkey); X509_free(cert);
        return "Failed to send CNXN";
    }
    LOGI("Sent CNXN (version=0x%08x)", ADB_VERSION_TLS);

    // Read response
    AdbMsg msg;
    if (!read_adb_msg(fd, msg)) {
        // Peer closed immediately — might be TLS-first
        LOGI("Peer closed after CNXN — retrying with TLS-first...");
        close(fd);

        // Reconnect for TLS-first attempt
        if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
            EVP_PKEY_free(pkey); X509_free(cert);
            return "DNS resolution failed on retry";
        }
        fd = socket(res->ai_family, SOCK_STREAM, 0);
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        if (connect(fd, res->ai_addr, res->ai_addrlen) < 0) {
            freeaddrinfo(res); close(fd); EVP_PKEY_free(pkey); X509_free(cert);
            return "TCP reconnect failed for TLS-first";
        }
        freeaddrinfo(res);
        LOGI("Reconnected — trying TLS-first...");

        SSL* ssl; SSL_CTX* ssl_ctx;
        std::string tls_err = do_tls_handshake(fd, pkey, cert, &ssl, &ssl_ctx);
        EVP_PKEY_free(pkey); X509_free(cert);
        if (!tls_err.empty()) {
            close(fd);
            return tls_err + " (re-pair needed?)";
        }

        // Clear timeouts
        tv.tv_sec = 0; tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        auto* h = new TlsHandle{fd, ssl, ssl_ctx, {}, {}, false};
        jlong handle = g_next_handle.fetch_add(1);
        { std::lock_guard<std::mutex> lock(g_handles_mu); g_handles[handle] = h; }
        *out_handle = handle;
        return "";
    }

    LOGI("Received msg cmd=0x%08x arg0=0x%08x", msg.cmd, msg.arg0);

    if (msg.cmd == A_CNXN) {
        // Got CNXN — now expect STLS
        LOGI("Received CNXN from device, waiting for STLS...");
        if (!read_adb_msg(fd, msg)) {
            close(fd); EVP_PKEY_free(pkey); X509_free(cert);
            return "Connection dropped after CNXN (expected STLS)";
        }
        LOGI("Received msg cmd=0x%08x after CNXN", msg.cmd);
    }

    if (msg.cmd == A_STLS) {
        LOGI("Received STLS — upgrading to TLS");
        // Send STLS response
        if (!write_adb_msg(fd, A_STLS, ADB_VERSION_TLS, 0, nullptr, 0)) {
            close(fd); EVP_PKEY_free(pkey); X509_free(cert);
            return "Failed to send STLS response";
        }

        SSL* ssl; SSL_CTX* ssl_ctx;
        std::string tls_err = do_tls_handshake(fd, pkey, cert, &ssl, &ssl_ctx);
        EVP_PKEY_free(pkey); X509_free(cert);
        if (!tls_err.empty()) {
            close(fd);
            return tls_err + " (re-pair needed?)";
        }

        // Clear timeouts
        tv.tv_sec = 0; tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        auto* h = new TlsHandle{fd, ssl, ssl_ctx, {}, {}, true};
        jlong handle = g_next_handle.fetch_add(1);
        { std::lock_guard<std::mutex> lock(g_handles_mu); g_handles[handle] = h; }
        *out_handle = handle;
        LOGI("STLS upgrade complete — ADB connection ready");
        return "";
    }

    // Unexpected message
    close(fd); EVP_PKEY_free(pkey); X509_free(cert);
    char err[128];
    snprintf(err, sizeof(err), "Unexpected ADB response: cmd=0x%08X (expected CNXN or STLS)", msg.cmd);
    return err;
}


// ── JNI: TLS Connect ────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeConnect(
        JNIEnv* env, jobject /* thiz */,
        jstring j_host, jint port, jbyteArray j_rsa_priv_key_der) {

    const char* host = env->GetStringUTFChars(j_host, nullptr);
    jsize key_len = env->GetArrayLength(j_rsa_priv_key_der);
    jbyte* key_data = env->GetByteArrayElements(j_rsa_priv_key_der, nullptr);

    jlong handle = -1;
    std::string err = do_tls_connect(host, port,
                                      reinterpret_cast<const uint8_t*>(key_data),
                                      (size_t)key_len, &handle);

    env->ReleaseByteArrayElements(j_rsa_priv_key_der, key_data, JNI_ABORT);
    env->ReleaseStringUTFChars(j_host, host);

    if (!err.empty()) {
        jclass ioex = env->FindClass("java/io/IOException");
        env->ThrowNew(ioex, err.c_str());
        return -1;
    }
    return handle;
}


// ── JNI: Check if STLS path was used ───────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeIsStlsPath(
        JNIEnv* env, jobject /* thiz */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_handles_mu);
    auto it = g_handles.find(handle);
    if (it != g_handles.end()) {
        return it->second->stls_path ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}


// ── JNI: TLS Read (exact) ──────────────────────────────────────────────────

extern "C" JNIEXPORT jbyteArray JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeRead(
        JNIEnv* env, jobject /* thiz */, jlong handle, jint len) {

    TlsHandle* h = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handles_mu);
        auto it = g_handles.find(handle);
        if (it != g_handles.end()) h = it->second;
    }
    if (!h || !h->ssl) return nullptr;

    std::vector<uint8_t> buf(len);
    size_t off = 0;
    {
        std::lock_guard<std::mutex> lock(h->read_mu);
        while (off < (size_t)len) {
            int n = SSL_read(h->ssl, buf.data() + off, (int)(len - off));
            if (n <= 0) {
                LOGE("SSL_read error: %d (ssl_err=%d)", n, SSL_get_error(h->ssl, n));
                return nullptr;
            }
            off += n;
        }
    }

    jbyteArray result = env->NewByteArray(len);
    env->SetByteArrayRegion(result, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    return result;
}


// ── JNI: TLS Write ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeWrite(
        JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray j_data) {

    TlsHandle* h = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handles_mu);
        auto it = g_handles.find(handle);
        if (it != g_handles.end()) h = it->second;
    }
    if (!h || !h->ssl) return JNI_FALSE;

    jsize data_len = env->GetArrayLength(j_data);
    jbyte* data = env->GetByteArrayElements(j_data, nullptr);

    bool ok = true;
    {
        std::lock_guard<std::mutex> lock(h->write_mu);
        size_t off = 0;
        while (off < (size_t)data_len) {
            int n = SSL_write(h->ssl, data + off, (int)(data_len - off));
            if (n <= 0) {
                LOGE("SSL_write error: %d (ssl_err=%d)", n, SSL_get_error(h->ssl, n));
                ok = false;
                break;
            }
            off += n;
        }
    }

    env->ReleaseByteArrayElements(j_data, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}


// ── JNI: TLS Shutdown (unblock reads without freeing) ───────────────────────

extern "C" JNIEXPORT void JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeShutdown(
        JNIEnv* env, jobject /* thiz */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_handles_mu);
    auto it = g_handles.find(handle);
    if (it != g_handles.end()) {
        TlsHandle* h = it->second;
        if (h->fd >= 0) {
            shutdown(h->fd, SHUT_RDWR);
        }
    }
}


// ── JNI: TLS Close ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_tech_devline_scropy_1ui_adb_AdbTlsSocket_nativeClose(
        JNIEnv* env, jobject /* thiz */, jlong handle) {

    TlsHandle* h = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handles_mu);
        auto it = g_handles.find(handle);
        if (it != g_handles.end()) {
            h = it->second;
            g_handles.erase(it);
        }
    }
    if (h) {
        if (h->ssl) {
            SSL_shutdown(h->ssl);
            SSL_free(h->ssl);
        }
        if (h->ssl_ctx) SSL_CTX_free(h->ssl_ctx);
        if (h->fd >= 0) close(h->fd);
        delete h;
        LOGI("TLS connection %lld closed", (long long)handle);
    }
}
