// JNI bridge between SAM's Kotlin layer (ir.elviran.sam.ai.local.LlamaNative) and llama.cpp.
//
// Real inference: tokenize -> decode -> sampler-chain token selection -> detokenize, running fully
// on the device CPU (arm64-v8a). No network. Generation is interruptible via an atomic stop flag.
//
// This file is only compiled when the native build is enabled (Gradle property `samLlama`). It is
// written against the llama.cpp API around the tag pinned in CMakeLists.txt; if that tag is bumped,
// re-verify the few API calls marked below. Because the agent environment cannot run the Android
// NDK, this bridge is delivered as source and must be built + verified on CI / device (reported as
// BLOCKED for device verification).

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include "llama.h"

#define LOG_TAG "samllama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct SamContext {
    llama_context *ctx = nullptr;
    llama_model *model = nullptr;
    int n_threads = 4;
    std::atomic<bool> stop{false};
};

static std::once_flag g_backend_once;

static void ensure_backend() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
    });
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_loadModel(JNIEnv *env, jobject, jstring path, jint nGpuLayers) {
    ensure_backend();
    std::string p = jstr(env, path);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers; // 0 = CPU only (safe default on Android)
    llama_model *model = llama_load_model_from_file(p.c_str(), mparams);
    if (!model) { LOGE("load model failed: %s", p.c_str()); return 0; }
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_freeModel(JNIEnv *, jobject, jlong modelHandle) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (model) llama_free_model(model);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_newContext(JNIEnv *, jobject, jlong modelHandle, jint nCtx, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (!model) return 0;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) (nCtx > 0 ? nCtx : 4096);
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context *ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) { LOGE("new context failed"); return 0; }
    auto *sc = new SamContext();
    sc->ctx = ctx; sc->model = model; sc->n_threads = cparams.n_threads;
    return reinterpret_cast<jlong>(sc);
}

extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_freeContext(JNIEnv *, jobject, jlong ctxHandle) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (!sc) return;
    if (sc->ctx) llama_free(sc->ctx);
    delete sc;
}

extern "C" JNIEXPORT void JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_requestStop(JNIEnv *, jobject, jlong ctxHandle) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    if (sc) sc->stop.store(true);
}

static std::string token_to_piece(llama_model *model, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(model, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_generate(JNIEnv *env, jobject, jlong ctxHandle, jlong modelHandle,
                                                  jstring promptJ, jint maxTokens) {
    auto *sc = reinterpret_cast<SamContext *>(ctxHandle);
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (!sc || !sc->ctx || !model) return env->NewStringUTF("");
    sc->stop.store(false);

    std::string prompt = jstr(env, promptJ);

    // --- tokenize prompt ---
    const int n_prompt_max = 8192;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tok = llama_tokenize(model, prompt.c_str(), (int) prompt.size(),
                               tokens.data(), (int) tokens.size(), true, true);
    if (n_tok < 0) { LOGE("tokenize overflow"); return env->NewStringUTF(""); }
    tokens.resize(n_tok);

    // --- build a simple sampler chain (top_k -> top_p -> temp -> dist) ---
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // --- decode prompt ---
    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
    if (llama_decode(sc->ctx, batch) != 0) {
        llama_sampler_free(smpl);
        LOGE("decode prompt failed");
        return env->NewStringUTF("");
    }

    std::string out;
    int generated = 0;
    const int cap = maxTokens > 0 ? maxTokens : 384;
    while (generated < cap && !sc->stop.load()) {
        llama_token next = llama_sampler_sample(smpl, sc->ctx, -1);
        if (llama_token_is_eog(model, next)) break;
        out += token_to_piece(model, next);
        llama_sampler_accept(smpl, next);
        llama_batch nb = llama_batch_get_one(&next, 1);
        if (llama_decode(sc->ctx, nb) != 0) break;
        generated++;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_elviran_sam_ai_local_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    ensure_backend();
    return env->NewStringUTF(llama_print_system_info());
}
