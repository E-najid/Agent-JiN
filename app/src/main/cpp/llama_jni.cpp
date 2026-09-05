#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <unistd.h>

#include "llama.h"

#ifdef AGENTJIN_HAS_MTMD
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

#ifndef JNIEXPORT
#define JNIEXPORT __attribute__((visibility("default")))
#endif

#define LOG_TAG "AgentJiN-llama"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
#ifdef AGENTJIN_HAS_MTMD
    mtmd_context *mtmd = nullptr;
#endif
    int n_ctx = 0;
    int n_batch = 256;
    int n_threads = 0;
    std::atomic<bool> abort_flag{false};
    std::mutex mu;
};

static std::mutex g_backend_mu;
static bool g_backend_ready = false;

static void android_log_callback(enum ggml_log_level level, const char *text, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    if (level >= GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level >= GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, LOG_TAG, "%s", text);
}

static std::string jstring_to_utf8(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *chars = env->GetStringUTFChars(js, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

static int default_threads() {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n < 2) return 1;
    // Leave headroom so the UI stays responsive on 3GB devices.
    int t = static_cast<int>(n) - 1;
    if (t < 1) t = 1;
    if (t > 4) t = 4;
    return t;
}

static void free_sampler(Engine *e) {
    if (e->sampler) {
        llama_sampler_free(e->sampler);
        e->sampler = nullptr;
    }
}

static void make_sampler(Engine *e, float temp, const char *grammar, const llama_vocab *vocab) {
    free_sampler(e);
    llama_sampler_chain_params cparams = llama_sampler_chain_default_params();
    e->sampler = llama_sampler_chain_init(cparams);
    if (grammar && grammar[0] != '\0' && vocab) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar, "root");
        if (g) {
            llama_sampler_chain_add(e->sampler, g);
        } else {
            LOGe("grammar sampler failed to compile; continuing without grammar");
        }
    }
    llama_sampler_chain_add(e->sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_temp(temp <= 0.0f ? 0.0f : temp));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

static void clear_kv(llama_context *ctx) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }
}

static bool decode_tokens(Engine *e, const llama_token *tokens, int n) {
    int i = 0;
    const int n_batch = e->n_batch > 0 ? e->n_batch : 256;
    while (i < n) {
        int take = n - i;
        if (take > n_batch) take = n_batch;
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens + i), take);
        if (llama_decode(e->ctx, batch) != 0) {
            return false;
        }
        i += take;
    }
    return true;
}

static std::string token_to_piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, tok, big.data(), -n, 0, true);
        if (n < 0) return {};
        big.resize(n);
        return big;
    }
    return std::string(buf, n);
}

static bool ends_with_stop(const std::string &text, const std::vector<std::string> &stops) {
    for (const auto &s : stops) {
        if (!s.empty() && text.size() >= s.size() &&
            text.compare(text.size() - s.size(), s.size(), s) == 0) {
            return true;
        }
    }
    return false;
}

static std::vector<std::string> parse_stops(const std::string &joined) {
    std::vector<std::string> out;
    size_t start = 0;
    while (start < joined.size()) {
        size_t pos = joined.find('\n', start);
        if (pos == std::string::npos) {
            out.push_back(joined.substr(start));
            break;
        }
        out.push_back(joined.substr(start, pos - start));
        start = pos + 1;
    }
    return out;
}

static std::string generate_loop(JNIEnv *env, Engine *e, int max_tokens,
                                 const std::vector<std::string> &stops,
                                 jobject callback) {
    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    jmethodID onToken = nullptr;
    jmethodID shouldStop = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        shouldStop = env->GetMethodID(cls, "shouldStop", "()Z");
    }

    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        if (e->abort_flag.load()) break;
        if (shouldStop && env->CallBooleanMethod(callback, shouldStop)) break;

        llama_token tok = llama_sampler_sample(e->sampler, e->ctx, -1);
        llama_sampler_accept(e->sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        std::string piece = token_to_piece(vocab, tok);
        out += piece;
        if (onToken && !piece.empty()) {
            jstring jp = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jp);
            env->DeleteLocalRef(jp);
        }
        if (ends_with_stop(out, stops)) {
            // Strip the stop sequence.
            for (const auto &s : stops) {
                if (!s.empty() && out.size() >= s.size() &&
                    out.compare(out.size() - s.size(), s.size(), s) == 0) {
                    out.resize(out.size() - s.size());
                    break;
                }
            }
            break;
        }
        if (!decode_tokens(e, &tok, 1)) {
            LOGe("decode failed during generation");
            break;
        }
    }
    return out;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_initBackend(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_backend_mu);
    if (g_backend_ready) return;
    llama_log_set(android_log_callback, nullptr);
    llama_backend_init();
    g_backend_ready = true;
    LOGi("llama backend initialized");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads, jboolean use_mmap) {
    std::string path = jstring_to_utf8(env, jpath);
    if (path.empty()) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGe("failed to load model: %s", path.c_str());
        return 0;
    }

    int threads = n_threads > 0 ? n_threads : default_threads();
    int ctx_size = n_ctx > 0 ? n_ctx : 1024;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size;
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGe("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->n_ctx = ctx_size;
    engine->n_batch = 256;
    engine->n_threads = threads;
    LOGi("loaded %s ctx=%d threads=%d mmap=%d", path.c_str(), ctx_size, threads, (int) use_mmap);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_loadMmproj(
        JNIEnv *env, jobject, jlong handle, jstring jpath) {
#ifdef AGENTJIN_HAS_MTMD
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->model) return JNI_FALSE;
    std::string path = jstring_to_utf8(env, jpath);
    std::lock_guard<std::mutex> lock(e->mu);
    if (e->mtmd) {
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
    }
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = false;
    p.n_threads = e->n_threads;
    p.print_timings = false;
    p.warmup = false;
    e->mtmd = mtmd_init_from_file(path.c_str(), e->model, p);
    if (!e->mtmd) {
        LOGe("mtmd_init_from_file failed: %s", path.c_str());
        return JNI_FALSE;
    }
    if (!mtmd_support_vision(e->mtmd)) {
        LOGe("mmproj loaded but vision is not supported");
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
#else
    (void) env;
    (void) handle;
    (void) jpath;
    LOGe("libmtmd was not linked into this build");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_unload(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->mu);
    e->abort_flag.store(true);
    free_sampler(e);
#ifdef AGENTJIN_HAS_MTMD
    if (e->mtmd) {
        mtmd_free(e->mtmd);
        e->mtmd = nullptr;
    }
#endif
    if (e->ctx) {
        llama_free(e->ctx);
        e->ctx = nullptr;
    }
    if (e->model) {
        llama_model_free(e->model);
        e->model = nullptr;
    }
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_abort(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->abort_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_reset(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->mu);
    if (e->ctx) clear_kv(e->ctx);
    e->abort_flag.store(false);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint max_tokens, jfloat temp,
        jstring jstops, jstring jgrammar, jobject callback) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->model || !e->ctx) {
        return env->NewStringUTF("");
    }
    std::string prompt = jstring_to_utf8(env, jprompt);
    std::string grammar = jstring_to_utf8(env, jgrammar);
    std::vector<std::string> stops = parse_stops(jstring_to_utf8(env, jstops));

    std::lock_guard<std::mutex> lock(e->mu);
    e->abort_flag.store(false);
    clear_kv(e->ctx);

    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    make_sampler(e, temp, grammar.c_str(), vocab);

    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n <= 0) {
        LOGe("tokenize failed (%d)", n);
        return env->NewStringUTF("");
    }
    tokens.resize(n);
    if (n >= e->n_ctx - 8) {
        LOGe("prompt too long for context (%d >= %d)", n, e->n_ctx);
        return env->NewStringUTF("");
    }
    if (!decode_tokens(e, tokens.data(), n)) {
        LOGe("prompt decode failed");
        return env->NewStringUTF("");
    }
    int cap = max_tokens > 0 ? max_tokens : 256;
    std::string out = generate_loop(env, e, cap, stops, callback);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_generateVision(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jbyteArray jrgb,
        jint width, jint height, jint max_tokens, jfloat temp, jstring jstops) {
#ifndef AGENTJIN_HAS_MTMD
    (void) handle;
    (void) jprompt;
    (void) jrgb;
    (void) width;
    (void) height;
    (void) max_tokens;
    (void) temp;
    (void) jstops;
    return env->NewStringUTF("ERROR: vision runtime (libmtmd) was not compiled into this build");
#else
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->model || !e->ctx || !e->mtmd) {
        return env->NewStringUTF("ERROR: vision model is not loaded");
    }
    if (!jrgb || width <= 0 || height <= 0) {
        return env->NewStringUTF("ERROR: empty screenshot");
    }
    std::string prompt = jstring_to_utf8(env, jprompt);
    std::vector<std::string> stops = parse_stops(jstring_to_utf8(env, jstops));

    jsize nbytes = env->GetArrayLength(jrgb);
    if (nbytes != width * height * 3) {
        return env->NewStringUTF("ERROR: RGB buffer size does not match width*height*3");
    }
    std::vector<unsigned char> rgb(nbytes);
    env->GetByteArrayRegion(jrgb, 0, nbytes, reinterpret_cast<jbyte *>(rgb.data()));

    std::lock_guard<std::mutex> lock(e->mu);
    e->abort_flag.store(false);
    clear_kv(e->ctx);

    mtmd_bitmap *bmp = mtmd_bitmap_init(static_cast<uint32_t>(width),
                                        static_cast<uint32_t>(height),
                                        rgb.data());
    if (!bmp) {
        return env->NewStringUTF("ERROR: failed to create image bitmap");
    }

    const char *marker = mtmd_get_marker(e->mtmd);
    if (!marker) marker = mtmd_default_marker();
    std::string full = prompt;
    if (full.find(marker) == std::string::npos) {
        full = std::string(marker) + "\n" + prompt;
    }

    mtmd_input_text text{};
    text.text = full.c_str();
    text.text_len = full.size();
    text.add_special = true;
    text.parse_special = true;

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[1] = {bmp};
    int32_t tok_rc = mtmd_tokenize(e->mtmd, chunks, &text, bitmaps, 1);
    if (tok_rc != 0) {
        mtmd_bitmap_free(bmp);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("ERROR: vision tokenize failed");
    }

    llama_pos n_past = 0;
    int32_t eval_rc = mtmd_helper_eval_chunks(
            e->mtmd, e->ctx, chunks, 0, 0, e->n_batch, true, &n_past);
    mtmd_bitmap_free(bmp);
    mtmd_input_chunks_free(chunks);
    if (eval_rc != 0) {
        return env->NewStringUTF("ERROR: vision eval failed");
    }

    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    make_sampler(e, temp, "", vocab);
    int cap = max_tokens > 0 ? max_tokens : 192;
    std::string out = generate_loop(env, e, cap, stops, nullptr);
    return env->NewStringUTF(out.c_str());
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ngi_agentjin_core_inference_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF("llama.cpp CPU mmap (agentjin_llama)");
}
