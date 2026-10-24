// SPDX-License-Identifier: Apache-2.0
// Bounded, isolated BoringSSL SPAKE2+AES128-GCM JNI backend for on-device ADB.
// Inspired by Android AOSP's pairing_auth and Shizuku's Apache-2.0 JNI reference.
// Do not package as a production build before release provenance/security audit.
#include <jni.h>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
constexpr uint8_t kClientName[] = "adb pair client";
constexpr uint8_t kServerName[] = "adb pair server";
constexpr uint8_t kKdfLabel[] = "adb pairing_auth aes-128-gcm key";
constexpr size_t kPasswordSize = 70;
constexpr size_t kMsgSize = 32;
constexpr size_t kKeySize = 16;
constexpr size_t kPeerInfoSize = 8192;
constexpr size_t kTagSize = 16;
constexpr size_t kNonceSize = 12;
using SpakePtr = std::unique_ptr<SPAKE2_CTX, decltype(&SPAKE2_CTX_free)>;
using AeadPtr = std::unique_ptr<EVP_AEAD_CTX, decltype(&EVP_AEAD_CTX_free)>;

struct State {
  SpakePtr spake{nullptr, &SPAKE2_CTX_free};
  AeadPtr cipher{nullptr, &EVP_AEAD_CTX_free};
  std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> message{};
  size_t message_size = 0;
  uint64_t encrypt_counter = 0;
  uint64_t decrypt_counter = 0;
  ~State() {OPENSSL_cleanse(message.data(),message.size());}
};
std::mutex g_lock;
std::unordered_map<jlong,std::unique_ptr<State>> g_states;
jlong g_next = 1;

void error(JNIEnv* env) {
  jclass cls = env->FindClass("java/security/GeneralSecurityException");
  if(cls) env->ThrowNew(cls,"ADB pairing cryptographic operation failed");
}

State* find(jlong handle) {
  auto it = g_states.find(handle);
  return it==g_states.end()?nullptr:it->second.get();
}

std::array<uint8_t,kNonceSize> nonce(uint64_t counter) {
  std::array<uint8_t,kNonceSize> bytes{};
  for(unsigned i=0;i<8;i++)bytes[i]=static_cast<uint8_t>(counter>>(i*8));
  return bytes;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeCreate(JNIEnv* env,jclass,jbyteArray j_password) {
  if(!j_password || env->GetArrayLength(j_password) != kPasswordSize) {error(env);return 0;}
  std::array<uint8_t,kPasswordSize> password{};
  env->GetByteArrayRegion(j_password,0,password.size(),reinterpret_cast<jbyte*>(password.data()));
  if(env->ExceptionCheck()) {OPENSSL_cleanse(password.data(),password.size());return 0;}
  auto state=std::make_unique<State>();
  state->spake.reset(SPAKE2_CTX_new(spake2_role_alice,kClientName,sizeof(kClientName),kServerName,sizeof(kServerName)));
  bool created = state->spake && SPAKE2_generate_msg(state->spake.get(),state->message.data(),
                        &state->message_size,state->message.size(),password.data(),password.size())==1
                      && state->message_size==kMsgSize;
  OPENSSL_cleanse(password.data(),password.size());
  if(!created){error(env);return 0;}
  std::lock_guard<std::mutex> lock(g_lock);
  if(g_states.size()>=2 || g_next==std::numeric_limits<jlong>::max()) {error(env);return 0;}
  jlong id=g_next++;
  g_states.emplace(id,std::move(state));
  return id;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeMessage(JNIEnv* env,jclass,jlong h) {
  std::lock_guard<std::mutex> lock(g_lock);State* s=find(h);
  if(!s || s->message_size!=kMsgSize) {error(env);return nullptr;}
  jbyteArray result=env->NewByteArray(kMsgSize);
  if(result)env->SetByteArrayRegion(result,0,kMsgSize,reinterpret_cast<jbyte*>(s->message.data()));
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeAccept(JNIEnv* env,jclass,jlong h,jbyteArray j_peer) {
  if(!j_peer || env->GetArrayLength(j_peer)!=kMsgSize) {error(env);return JNI_FALSE;}
  std::array<uint8_t,kMsgSize> peer{};
  env->GetByteArrayRegion(j_peer,0,kMsgSize,reinterpret_cast<jbyte*>(peer.data()));
  if(env->ExceptionCheck())return JNI_FALSE;
  std::lock_guard<std::mutex> lock(g_lock);State* s=find(h);
  if(!s || s->cipher) {OPENSSL_cleanse(peer.data(),peer.size());error(env);return JNI_FALSE;}
  std::array<uint8_t,SPAKE2_MAX_KEY_SIZE> shared{};
  std::array<uint8_t,kKeySize> key{};
  size_t len=0;
  bool ok=SPAKE2_process_msg(s->spake.get(),shared.data(),&len,shared.size(),peer.data(),peer.size())==1
       && HKDF(key.data(),key.size(),EVP_sha256(),shared.data(),len,nullptr,0,kKdfLabel,sizeof(kKdfLabel)-1)==1;
  if(ok)s->cipher.reset(EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(),key.data(),key.size(),EVP_AEAD_DEFAULT_TAG_LENGTH));
  ok=ok && s->cipher;
  OPENSSL_cleanse(peer.data(),peer.size());
  OPENSSL_cleanse(shared.data(),shared.size());
  OPENSSL_cleanse(key.data(),key.size());
  if(!ok) {error(env);return JNI_FALSE;}
  return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeSeal(JNIEnv* env,jclass,jlong h,jbyteArray j_plain) {
  if(!j_plain || env->GetArrayLength(j_plain)!=kPeerInfoSize) {error(env);return nullptr;}
  std::array<uint8_t,kPeerInfoSize> plain{};
  env->GetByteArrayRegion(j_plain,0,plain.size(),reinterpret_cast<jbyte*>(plain.data()));
  if(env->ExceptionCheck())return nullptr;
  std::array<uint8_t,kPeerInfoSize+kTagSize> cipher{};
  size_t len=0;
  bool ok=false;
  {
    std::lock_guard<std::mutex> lock(g_lock);
    State* s=find(h);
    if(s && s->cipher && s->encrypt_counter!=std::numeric_limits<uint64_t>::max()) {
      const auto iv=nonce(s->encrypt_counter);
      ok=EVP_AEAD_CTX_seal(s->cipher.get(),cipher.data(),&len,cipher.size(),iv.data(),iv.size(),
                plain.data(),plain.size(),nullptr,0)==1 && len==cipher.size();
      if(ok)s->encrypt_counter++;
    }
  }
  OPENSSL_cleanse(plain.data(),plain.size());
  jbyteArray result=nullptr;
  if(ok) {
    result=env->NewByteArray(cipher.size());
    if(result)env->SetByteArrayRegion(result,0,cipher.size(),reinterpret_cast<jbyte*>(cipher.data()));
  } else error(env);
  OPENSSL_cleanse(cipher.data(),cipher.size());
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeOpen(JNIEnv* env,jclass,jlong h,jbyteArray j_cipher) {
  if(!j_cipher || env->GetArrayLength(j_cipher)!=kPeerInfoSize+kTagSize) {error(env);return nullptr;}
  std::array<uint8_t,kPeerInfoSize+kTagSize> cipher{};
  env->GetByteArrayRegion(j_cipher,0,cipher.size(),reinterpret_cast<jbyte*>(cipher.data()));
  if(env->ExceptionCheck())return nullptr;
  std::array<uint8_t,kPeerInfoSize> plain{};
  size_t len=0;
  bool ok=false;
  {
    std::lock_guard<std::mutex> lock(g_lock);
    State* s=find(h);
    if(s && s->cipher && s->decrypt_counter!=std::numeric_limits<uint64_t>::max()) {
      const auto iv=nonce(s->decrypt_counter);
      ok=EVP_AEAD_CTX_open(s->cipher.get(),plain.data(),&len,plain.size(),iv.data(),iv.size(),
                cipher.data(),cipher.size(),nullptr,0)==1 && len==plain.size();
      if(ok)s->decrypt_counter++;
    }
  }
  OPENSSL_cleanse(cipher.data(),cipher.size());
  jbyteArray result=nullptr;
  if(ok) {
    result=env->NewByteArray(plain.size());
    if(result)env->SetByteArrayRegion(result,0,plain.size(),reinterpret_cast<jbyte*>(plain.data()));
  } else error(env);
  OPENSSL_cleanse(plain.data(),plain.size());
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sid_fiveberinspector_OnDeviceAdbNativeCrypto_nativeDestroy(JNIEnv*,jclass,jlong h) {
  std::lock_guard<std::mutex> lock(g_lock);
  g_states.erase(h); // native unique_ptr destructs session and key material
}
