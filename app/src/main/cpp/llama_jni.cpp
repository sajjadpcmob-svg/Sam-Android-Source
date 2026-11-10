// JNI bridge between SAM's Kotlin layer (ir.elviran.sam.ai.local.LlamaNative) and llama.cpp.
//
// Real inference: tokenize -> decode (in n_batch chunks) -> sampler-chain token selection ->
// detokenize, on the device CPU (arm64-v8a). No network.
//
// Written against the llama.cpp API of the tag pinned in CMakeLists.txt (b4409): it still uses
// llama_load_model_from_file / llama_new_context_with_model / llama_free_model /
// llama_kv_cache_clear / llama_token_is_eog(model, ...) / llama_tokenize(model, ...). Those names were
// renamed in later llama.cpp releases, so if the tag is bumped every call marked [API] must be
// re-checked. This file has NOT been compiled against the real b4409 header in the agent environment
// (no NDK / no network there); it must be built on CI (-PsamLlama) and verified on a device.
//
// Per-request guarantees:
//  * the KV cache is cleared when a request starts and again on every exit path (KvGuard), so no token
//    state of a previous request can reach the next one;
//  * the stop flag is only cleared by resetContext() (called by Kotlin before the generation becomes
//    stoppable), never inside generate(), so a stop that arrives right at the start is not lost;
//  * generate() holds the per-context mutex, so two generations (or a generation and a free) can never
//    run on the same context at the same time;
//  * errors are reported as Java exceptions; text is returned as raw UTF-8 bytes.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "samllama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct SamContext {
    llama_context *ctx = nullptr;
    llama_model *model = nullptr; // owned by Kotlin (freeModel), only referenced here
    int n_threads = 4;
    std::atomic<bool> stop{false};
    std::mutex gen_mutex; // serialises generate / reset / free on this context
};

static std::once_flag g_backend_once;

static void ensure_backend() {
    std::call_once(g_backend_once, []() { llama_backend_init(); }); // [API] no-arg in b4409
}

static void throw_java(JNIEnv *env, const char *cls, const char *msg) {
    jclass c = env->FindClass(cls);
    if (c) { env->ThrowNew(c, msg); env->DeleteLocalRef(c); }
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::vector<std::string> jstrings(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arr, i);
        std::string v = jstr(env, s);
        if (s) env->DeleteLocalRef(s);
        if (!v.empty()) out.push_back(v);
    }
    return out;
}

// Clears the KV cache when it goes out of scope (every exit path of generate()).
struct KvGuard {
    llama_context *ctx;
    ~KvGuard() { if (ctx) llama_kv_cache_clear(ctx); } // [API] renamed in later releases
};

struct SamplerGuard {
    llama_sampler *s;
    ~SamplerGuard() { if (s) llama_sampler_free(s); }
};

extern "C" JNIEXPORT jlong JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_loadModel(JNIEnv *env, jobject, jstring path, jint nGpuLayers) {
    ensure_backend();
    std::string p = jstr(env, path);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers; // 0 = CPU only (safe default on Android)
    llama_model *model = llama_load_model_from_file(p.c_str(), mparams); // [API]
    if (!model) { LOGE("load model failed: %s", p.c_str()); return 0; }
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_freeModel(JNIEnv *, jobject, jlong modelHandle) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (model) llama_free_model(model); // [API]
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_newContext(JNIEnv *, jobject, jlong modelHandle, jint nCtx, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (!model) return 0;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) (nCtx > 0 ? nCtx : 4096);
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context *ctx = llama_new_context_with_model(model, cparams); // [API]
    if (!ctx) { LOGE("new context failed"); return 0; }
    auto *sc = new SamContext();
    sc->ctx = ctx; sc->model = model; sc->n_threads = cparams.n_threads;
    return reinterpret_cast<jlong>(sc);
}

// Kotlin (LlmSession) only calls this while no generation is running; the lock is a second line of defence.
extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_freeContext(JNIEnv *, jobject, jlong ctxHandle) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (!sc) return;
    sc->stop.store(true);
    {
        std::lock_guard<std::mutex> lk(sc->gen_mutex);
        if (sc->ctx) { llama_free(sc->ctx); sc->ctx = nullptr; }
    }
    delete sc;
}

extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_requestStop(JNIEnv *, jobject, jlong ctxHandle) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (sc) sc->stop.store(true); // atomic only: safe to call while generate() runs
}

// Empties the KV cache and clears a pending stop request.
extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_resetContext(JNIEnv *, jobject, jlong ctxHandle) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (!sc) return;
    std::lock_guard<std::mutex> lk(sc->gen_mutex);
    if (sc->ctx) llama_kv_cache_clear(sc->ctx); // [API]
    sc->stop.store(false);
}

static std::string token_to_piece(const llama_model *model, llama_token tok) {
    std::string piece(64, '\0');
    int n = llama_token_to_piece(model, tok, &piece[0], (int) piece.size(), 0, true); // [API]
    if (n < 0) { // buffer too small: -n is the required size
        piece.assign((size_t) -n, '\0');
        n = llama_token_to_piece(model, tok, &piece[0], (int) piece.size(), 0, true);
    }
    if (n < 0) return {};
    piece.resize((size_t) n);
    return piece;
}

// Tokenizes with the same flags generate() uses; returns the token count, or -1 on failure.
static int tokenize_all(const llama_model *model, const std::string &text, bool add_special, std::vector<llama_token> *out) {
    int n = llama_tokenize(model, text.c_str(), (int) text.size(), nullptr, 0, add_special, /*parse_special=*/true); // [API]
    if (n < 0) n = -n; // negative = required size
    if (n == 0) { if (out) out->clear(); return 0; }
    if (!out) return n;
    out->resize((size_t) n);
    int n2 = llama_tokenize(model, text.c_str(), (int) text.size(), out->data(), (int) out->size(), add_special, true);
    if (n2 < 0) return -1;
    out->resize((size_t) n2);
    return n2;
}

extern "C" JNIEXPORT jint JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_tokenCount(JNIEnv *env, jobject, jlong modelHandle, jstring textJ, jboolean addSpecial) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (!model) { throw_java(env, "java/lang/IllegalStateException", "model handle is null"); return 0; }
    int n = tokenize_all(model, jstr(env, textJ), addSpecial == JNI_TRUE, nullptr);
    if (n < 0) { throw_java(env, "java/lang/IllegalStateException", "tokenize failed"); return 0; }
    return n;
}

// Cuts `out` at the first stop string; returns true when one was found.
static bool cut_at_stop(std::string &out, const std::vector<std::string> &stops) {
    size_t best = std::string::npos;
    for (const auto &s : stops) {
        size_t p = out.find(s);
        if (p != std::string::npos && p < best) best = p;
    }
    if (best == std::string::npos) return false;
    out.resize(best);
    return true;
}

static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_generate(JNIEnv *env, jobject, jlong ctxHandle, jstring promptJ, jint maxTokens,
                                                  jboolean addSpecial, jobjectArray stopsJ) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (!sc) { throw_java(env, "java/lang/IllegalStateException", "context handle is null"); return nullptr; }
    std::lock_guard<std::mutex> lk(sc->gen_mutex);
    if (!sc->ctx) { throw_java(env, "java/lang/IllegalStateException", "context already freed"); return nullptr; }
    llama_context *ctx = sc->ctx;
    llama_model *model = sc->model;

    const std::string prompt = jstr(env, promptJ);
    const std::vector<std::string> stops = jstrings(env, stopsJ);

    // Fresh start for THIS request; cleared again on every exit path.
    llama_kv_cache_clear(ctx); // [API]
    KvGuard kv_guard{ctx};

    // --- tokenize (no fixed upper bound) ---
    std::vector<llama_token> tokens;
    int n_tok = tokenize_all(model, prompt, addSpecial == JNI_TRUE, &tokens);
    if (n_tok < 0) { throw_java(env, "java/lang/IllegalStateException", "tokenize failed"); return nullptr; }
    if (n_tok == 0) { throw_java(env, "java/lang/IllegalStateException", "empty prompt"); return nullptr; }
    const int n_ctx = (int) llama_n_ctx(ctx);
    if (n_tok >= n_ctx) { throw_java(env, "java/lang/IllegalStateException", "prompt_too_long"); return nullptr; }

    // --- decode the prompt in chunks of at most n_batch tokens ---
    const int n_batch = std::max(1, (int) llama_n_batch(ctx));
    int n_past = 0;
    for (int i = 0; i < n_tok; i += n_batch) {
        if (sc->stop.load()) return to_bytes(env, "");
        const int n_eval = std::min(n_batch, n_tok - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval); // [API] 2-arg form
        if (llama_decode(ctx, batch) != 0) { // [API]
            LOGE("decode prompt failed at %d/%d", i, n_tok);
            throw_java(env, "java/lang/IllegalStateException", "decode prompt failed");
            return nullptr;
        }
        n_past += n_eval;
    }

    // --- sampler chain (top_k -> top_p -> temp -> dist), new for every request ---
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    SamplerGuard sampler_guard{smpl};
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    int generated = 0;
    const int cap = maxTokens > 0 ? maxTokens : 384;
    while (generated < cap && n_past < n_ctx && !sc->stop.load()) {
        // llama_sampler_sample already accepts the chosen token into the chain.
        llama_token next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_token_is_eog(model, next)) break; // [API] takes the model in b4409
        out += token_to_piece(model, next);
        generated++;
        if (cut_at_stop(out, stops)) break;
        llama_batch nb = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, nb) != 0) { LOGE("decode token failed after %d tokens", generated); break; }
        n_past++;
    }
    return to_bytes(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    ensure_backend();
    return env->NewStringUTF(llama_print_system_info());
}
