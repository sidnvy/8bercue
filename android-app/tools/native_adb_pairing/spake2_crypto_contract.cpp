// SPDX-License-Identifier: Apache-2.0
// Isolated Android ARM64, NDK-BoringSSL protocol proof. No ADB IO, no real pairing.
// Based on AOSP adb pairing_auth and Shizuku's Apache-2.0 reference.
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>

namespace {
constexpr uint8_t kClientName[] = "adb pair client";
constexpr uint8_t kServerName[] = "adb pair server";
constexpr uint8_t kInfo[] = "adb pairing_auth aes-128-gcm key";
constexpr size_t kPasswordLen = 70;
constexpr size_t kAesKeyLen = 16;
constexpr size_t kNonceLen = 12;
constexpr size_t kPeerInfoLen = 8192;
using SpakePtr = std::unique_ptr<SPAKE2_CTX, decltype(&SPAKE2_CTX_free)>;
using AeadPtr = std::unique_ptr<EVP_AEAD_CTX, decltype(&EVP_AEAD_CTX_free)>;

struct KeyMaterial {
  std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> msg{};
  std::array<uint8_t, kAesKeyLen> aes_key{};
  size_t msg_len = 0;
  SpakePtr spake{nullptr, &SPAKE2_CTX_free};
  KeyMaterial(spake2_role_t role) {
    const bool client = role == spake2_role_alice;
    const auto* mine = client ? kClientName : kServerName;
    const auto* peer = client ? kServerName : kClientName;
    // sizeof includes the trailing NUL, matching the wire implementation.
    const size_t mine_len = client ? sizeof(kClientName) : sizeof(kServerName);
    const size_t peer_len = client ? sizeof(kServerName) : sizeof(kClientName);
    spake.reset(SPAKE2_CTX_new(role, mine, mine_len, peer, peer_len));
  }
  ~KeyMaterial() {
    OPENSSL_cleanse(msg.data(), msg.size());
    OPENSSL_cleanse(aes_key.data(), aes_key.size());
  }
  bool begin(const std::array<uint8_t, kPasswordLen>& password) {
    return spake && SPAKE2_generate_msg(spake.get(), msg.data(), &msg_len,
                                       msg.size(), password.data(), password.size()) == 1;
  }
  bool finish(const KeyMaterial& other) {
    std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> shared{};
    size_t shared_len = 0;
    const bool okay = SPAKE2_process_msg(spake.get(), shared.data(), &shared_len,
                                          shared.size(), other.msg.data(), other.msg_len) == 1
      && HKDF(aes_key.data(), aes_key.size(), EVP_sha256(), shared.data(), shared_len,
              nullptr, 0, kInfo, sizeof(kInfo) - 1) == 1;
    OPENSSL_cleanse(shared.data(), shared.size());
    return okay;
  }
};

bool authenticate(const KeyMaterial& sender, const KeyMaterial& receiver, bool tamper) {
  AeadPtr seal(EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), sender.aes_key.data(),
                   sender.aes_key.size(), EVP_AEAD_DEFAULT_TAG_LENGTH), &EVP_AEAD_CTX_free);
  AeadPtr open(EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), receiver.aes_key.data(),
                   receiver.aes_key.size(), EVP_AEAD_DEFAULT_TAG_LENGTH), &EVP_AEAD_CTX_free);
  if (!seal || !open) return false;
  std::array<uint8_t,kNonceLen> nonce{};
  std::array<uint8_t,kPeerInfoLen> plaintext{};
  // ADB peer info type is one byte, followed by a public key. Synthetic only.
  plaintext[1] = 'A';
  std::array<uint8_t,kPeerInfoLen+16> ciphertext{};
  std::array<uint8_t,kPeerInfoLen> recovered{};
  size_t sealed_len = 0, recovered_len = 0;
  bool valid = EVP_AEAD_CTX_seal(seal.get(), ciphertext.data(), &sealed_len,
                      ciphertext.size(), nonce.data(), nonce.size(),
                      plaintext.data(), plaintext.size(), nullptr, 0) == 1;
  if (valid && tamper) ciphertext[sealed_len-1] ^= 1;
  valid = valid && EVP_AEAD_CTX_open(open.get(), recovered.data(), &recovered_len,
                  recovered.size(), nonce.data(), nonce.size(), ciphertext.data(),
                  sealed_len, nullptr, 0) == 1;
  valid = valid && recovered_len == plaintext.size()
                  && CRYPTO_memcmp(plaintext.data(), recovered.data(), plaintext.size()) == 0;
  OPENSSL_cleanse(recovered.data(), recovered.size());
  OPENSSL_cleanse(ciphertext.data(), ciphertext.size());
  OPENSSL_cleanse(plaintext.data(), plaintext.size());
  return valid;
}

bool one_test(bool wrong_password) {
  std::array<uint8_t,kPasswordLen> password{};
  // Synthetic, deterministic inputs; no user/device secrets in logs or files.
  const uint8_t synthetic_code[] = {'0','1','2','3','4','5'};
  memcpy(password.data(), synthetic_code, sizeof(synthetic_code));
  for (size_t i=6;i<password.size();i++) password[i]=static_cast<uint8_t>(i);
  KeyMaterial alice(spake2_role_alice), bob(spake2_role_bob);
  bool ok = alice.begin(password);
  if (wrong_password) password[0] = '9';
  ok = ok && bob.begin(password);
  OPENSSL_cleanse(password.data(),password.size());
  ok = ok && alice.finish(bob) && bob.finish(alice);
  if (!ok) return false;
  // Matching password gives the same 128-bit key, mismatching ones must differ.
  const bool equal = CRYPTO_memcmp(alice.aes_key.data(),bob.aes_key.data(),kAesKeyLen)==0;
  if (wrong_password) return !equal && !authenticate(alice,bob,false);
  return equal && authenticate(alice,bob,false) && authenticate(bob,alice,false)
     && !authenticate(alice,bob,true);
}
} // namespace
int main() {
  if (!one_test(false) || !one_test(true)) {
    fputs("FAIL BoringSSL SPAKE2/AES-GCM contract\n",stderr);
    return 1;
  }
  puts("PASS BoringSSL SPAKE2 role agreement, wrong-password rejection, AES-GCM peer-info roundtrip and tamper rejection");
  return 0;
}
