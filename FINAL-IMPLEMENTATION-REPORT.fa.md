> ⚠️ گزارش تاریخی نسخه‌های قبلی؛ وضعیت فعلی در `FINAL-REPORT-v3.3.1.fa.md` است. دستور درست بیلد آفلاین: `-PsamLlama`.

# گزارش نهایی پیاده‌سازی — SAM نیتیو نسخه ۳.۳ (هستهٔ صوتی + هوش مصنوعی آفلاین)

> این گزارش دقیقاً وضعیت کاری که روی **سورس واقعی** انجام شده را شرح می‌دهد. هیچ ادعای «بیلد موفق» یا «تأییدشده روی دستگاه» در آن نیست؛ چون محیط ایجنت کاتلین/NDK را کامپایل نمی‌کند، تأیید نهایی فقط از اجرای GitHub Actions و نصب APK روی گوشی به‌دست می‌آید. هیچ کلاس نمایشی/Mock اضافه نشده؛ موتور llama.cpp واقعی با JNI/CMake پیاده شده است.

نام پکیج ریشه: `ir.elviran.sam` — خروجی: `SAM-Native-v3.3-Voice-OfflineAI.zip`.

---

## ۱. هدف این نسخه

دو خواسته اصلی:

1. **تثبیت هستهٔ صوتی:** زنجیرهٔ Wake Word → VAD → STT → پاسخ → TTS با قطع فوری (barge-in)، محافظت از نشست مکالمه (session guard)، fallback آنلاین→آفلاین و اجرای پایدار در سرویس پیش‌زمینه.
2. **هوش مصنوعی آفلاین واقعی:** پیاده‌سازی یک LLM روی دستگاه با `llama.cpp` (نه Mock) و اتصال آن به مسیر پاسخ‌دهی موجود (`AiRouter`) با سه حالت آنلاین/آفلاین/ترکیبی.

## ۲. اصل کار: تغییر جراحی‌گونه

معماری موجود دست‌نخورده ماند. `AiRouter` از قبل یک `LocalModelEngine` تزریقی و منطق مسیریابی `local.isReady()` داشت؛ تنها کاری که لازم بود، ساختن یک موتور واقعی llama.cpp و وصل‌کردن آن به همان نقطه بود. هیچ جدول دیتابیس، هیچ ماژول و هیچ منطق نامرتبطی تغییر نکرد و دادهٔ کاربر پاک نشد.

## ۳. هستهٔ صوتی — چه چیزی تثبیت شد

- **محافظت از نشست مکالمه (`ConversationController`):** یک `AtomicInteger turnSession` هر نوبت را شماره‌گذاری می‌کند؛ callback‌های دیررس (STT/TTS) که متعلق به نشست قبلی‌اند drop می‌شوند. `muteNow` و `shutdown` شمارهٔ نشست را بامپ می‌کنند تا هر پاسخ درحال‌اجرا بی‌اثر شود.
- **fallback یک‌باره:** در صورت شکست مسیر آنلاین، فقط یک‌بار به مسیر جایگزین می‌رود (بدون حلقهٔ بی‌پایان).
- **Wake Word (`WakeWordMatcher` + `WakeWordDetector`):** تشخیص عبارت «سام» روی دستگاه با Vosk؛ نرمال‌سازی گوناگونی‌های عربی/فارسی، رد هم‌آواهای نزدیک (شام، جام، سامسونگ) و **fail-safe روی confidence نامعتبر/NaN**: اعتماد نامشخص هرگز به معنای قطعیت کامل خوانده نمی‌شود.
- **سرویس پیش‌زمینه (`VoiceForegroundService`):** نوع `microphone` (الزامی از اندروید ۱۴)، اعلان دائمی با دکمهٔ قطع میکروفون/توقف، `START_STICKY`. ادعای دورزدن بهینه‌سازی باتری سامسونگ نشده؛ کاربر باید SAM را از بهینه‌سازی معاف کند.

## ۴. هوش مصنوعی آفلاین — موتور واقعی llama.cpp

فایل‌های جدید (همه در `app/src/main/java/ir/elviran/sam/ai/local/` مگر جایی که غیر این ذکر شود):

- `LlmState.kt` — چرخهٔ عمر مدل به‌صورت sealed (NotInstalled/Downloading/ReadyToLoad/Loading/Ready/Generating/Error) + کاتالوگ مدل (`LlmModelCatalog`, `LlmModelSpec` با `fitsRam`).
- `LlmModelManager.kt` — دانلود HTTPS با پیشرفت، بررسی حجم، **بررسی SHA-256** (وقتی pin شده)، import از فایل موجود، و سنجش RAM دستگاه. مدل هرگز داخل APK قرار نمی‌گیرد.
- `LlamaNative.kt` — سطح JNI (`System.loadLibrary("samllama")`) با پرچم `available`/`loadError`؛ اگر کتابخانه نیست، بدون کرش درجا به آنلاین/فرمان بازمی‌گردد.
- `LlamaCppEngine.kt` — پیاده‌سازی واقعی `LocalModelEngine`: load/free مدل و زمینه، تولید کرانه‌دار روی `Dispatchers.IO`، تک‌نمونهٔ process-wide، و ارائهٔ `state` برای UI. `isReady()` فقط وقتی true است که کتابخانه حاضر و زمینه بارگذاری شده باشد.
- `LlmSettings.kt` — `AiMode` (ONLINE/OFFLINE/HYBRID) + `LlmPrefs` (مدل انتخابی، threads، context، maxTokens) در DataStore.
- `cpp/CMakeLists.txt` + `cpp/llama_jni.cpp` — بیلد نیتیو واقعی: FetchContent از llama.cpp تگ `b4409`، tokenize→decode→sampler→detokenize روی CPU arm64-v8a، با پرچم توقف atomic.
- `OfflineLlmSection.kt` — کارت تنظیمات Compose: انتخاب حالت، انتخاب مدل، دانلود/بارگذاری/تخلیه و نمایش زندهٔ `LlmState` (۱:۱ با وضعیت موتور، بدون جعل وضعیت).

## ۵. اتصال نهایی (این نوبت انجام شد)

در `VoiceForegroundService.respond()` مسیریابی بر اساس حالت کاربر وصل شد:

- **ONLINE** → موتور محلی `NotConfiguredLocalEngine` (هرگز مدل محلی)، `networkAllowed=true`.
- **OFFLINE** → موتور `LlamaCppEngine`، `networkAllowed=false` (هیچ درخواست شبکه‌ای).
- **HYBRID** → موتور `LlamaCppEngine`؛ اگر بارگذاری شده باشد آفلاین پاسخ می‌دهد، وگرنه به آنلاین (`networkAllowed=true`) برمی‌گردد.

همچنین کارت `OfflineLlmSection` در صفحهٔ دستیار صوتی (زیر تنظیمات) نمایش داده شد تا کاربر حالت/مدل را مدیریت کند.

## ۶. بیلد نیتیو — گیت‌شده تا بیلد پیش‌فرض نشکند

بیلد llama.cpp در `app/build.gradle.kts` پشت پراپرتی Gradle به نام `samLlama` گیت شده است. بنابراین:

- `./gradlew assembleDebug` (پیش‌فرض، همان `build-android.yml`) دقیقاً مانند قبل کار می‌کند، بدون NDK؛ `LlamaNative.available == false` و اپ به آنلاین/فرمان درجا می‌کند.
- `./gradlew assembleDebug -PsamLlama` (ورک‌فلوی جداگانه `build-android-offline-ai.yml`) NDK و CMake را نصب می‌کند، `libsamllama.so` را برای arm64-v8a می‌سازد و APK با LLM آفلاین تولید می‌کند.

## ۷. تست‌های واحد (بدون دستگاه)

- `WakeWordMatcherTest` — افزوده شد: `nan_confidence_is_never_full_certainty` و `negative_confidence_blocked_by_gate` (تضمین fail-safe روی NaN/منفی).
- `LlmModelCatalogTest` — جدید: `fitsRam` (مرز دقیق حداقل RAM)، خالی‌نبودن کاتالوگ، `byId` برای شناسهٔ ناشناخته/null، و HTTPS + حجم معتبر هر مدل.
- تست‌های قبلی (VAD، Jalali، OfflineBrain، SafeMath، و…) دست‌نخورده ماندند.

این تست‌ها روی CI با `testDebugUnitTest` اجرا می‌شوند؛ چون محیط ایجنت کاتلین را کامپایل نمی‌کند، نتیجهٔ اجرای آن‌ها BLOCKED است و فقط روی CI تأیید می‌شود.

## ۸. دریافت مدل (کاربر)

مدل داخل ZIP نیست. دو راه:

1. **داخل اپ:** برو به دستیار صوتی ← تنظیمات ← کارت «هوش مصنوعی روی دستگاه»، مدل را انتخاب و دانلود کن، سپس «بارگذاری در حافظه».
2. **دستی (adb):** فایل GGUF را داخل حافظهٔ داخلی اپ کپی کن و از گزینهٔ import در مدیر مدل استفاده کن. مدل پیش‌فرض: `Qwen2.5-3B-Instruct-GGUF` (Q4_K_M).

دانلود از HuggingFace انجام می‌شود و حجم/هش بررسی می‌شود.

## ۹. محدودیت‌ها و صداقت فنی

- تأیید نهایی کامپایل Kotlin/NDK و اجرای دستگاه در این محیط ممکن نیست → همه در جدول پذیرش BLOCKED علامت خورده‌اند.
- `llama_jni.cpp` در برابر تگ `b4409` نوشته شده؛ اگر تگ بالاتر برود، چند فراخوانی API باید دوباره تطبیق داده شود.
- تشخیص فارسی گفتار (STT) به مدل Vosk دستگاه وابسته است.

## ۱۰. جدول پذیرش

وضعیت‌ها: **IMPLEMENTED** = کد واقعی نوشته شده؛ **INTEGRATED** = به مسیر اجرای اپ وصل شده؛ **TESTED** = تست واحد دارد؛ **BLOCKED** = نیازمند تأیید روی CI/دستگاه.

| # | قابلیت | وضعیت | شاهد |
|---|---|---|---|
| 1 | محافظت نشست مکالمه (session guard) | IMPLEMENTED + INTEGRATED | `ConversationController` (turnSession/drop/muteNow/shutdown) |
| 2 | fallback آنلاین→آفلاین یک‌باره | IMPLEMENTED + INTEGRATED | `ConversationController` + `AiRouter` |
| 3 | Wake Word «سام» + fail-safe NaN | IMPLEMENTED + TESTED | `WakeWordMatcher/Detector`، `WakeWordMatcherTest` |
| 4 | VAD / TTS / barge-in | IMPLEMENTED + INTEGRATED + TESTED | `VadController`، `VadControllerTest`، `ConversationController` |
| 5 | سرویس پیش‌زمینهٔ میکروفون | IMPLEMENTED + INTEGRATED | `VoiceForegroundService` |
| 6 | موتور llama.cpp واقعی (JNI/CMake) | IMPLEMENTED | `LlamaCppEngine`، `LlamaNative`، `cpp/llama_jni.cpp`، `cpp/CMakeLists.txt` |
| 7 | دانلود/وارد‌کردن مدل + SHA/حجم | IMPLEMENTED | `LlmModelManager` |
| 8 | کاتالوگ مدل + fitsRam | IMPLEMENTED + TESTED | `LlmModelCatalog`، `LlmModelCatalogTest` |
| 9 | اتصال LLM به `AiRouter` (ONLINE/OFFLINE/HYBRID) | IMPLEMENTED + INTEGRATED | `VoiceForegroundService.respond()` |
| 10 | UI دانلود/بارگذاری/حالت + نمایش LlmState | IMPLEMENTED + INTEGRATED | `OfflineLlmSection`، `VoiceAssistantScreen` |
| 11 | گیت‌شدن بیلد نیتیو (بیلد پیش‌فرض نشکند) | IMPLEMENTED | `app/build.gradle.kts` (`samLlama`) |
| 12 | ورک‌فلوی CI برای APK آفلاین | IMPLEMENTED | `build-android-offline-ai.yml` |
| 13 | کامپایل Kotlin و ساخت `libsamllama.so` | BLOCKED | فقط روی CI با `-PsamLlama` |
| 14 | اجرای استنتاج آفلاین روی دستگاه واقعی | BLOCKED | نصب APK روی Galaxy S24 Ultra |
| 15 | تشخیص واقعی گفتار فارسی (Vosk) روی دستگاه | BLOCKED | دانلود مدل + دستگاه |

مرحلهٔ بعد (سمت کاربر): محتوای `SAM-Native` را در ریشهٔ ریپو بگذار، ورک‌فلوی پیش‌فرض را برای APK معمولی و `Build SAM APK (Offline AI)` را برای APK با LLM آفلاین اجرا کن، سپس لاگ‌ها را برای رفع خطاهای احتمالی کامپایل بفرست.
