# DublajUz (Android)

Offline o'zbekcha video dublyaj: **Whisper (STT) → NLLB-200 (tarjima) → tahrir → VITS (o'zbek TTS) → MP4**.
Hammasi telefonda ishlaydi (faqat modellarni birinchi marta yuklash uchun internet kerak).

## GitHub'da APK yig'ish
1. Loyihani GitHub repo'ga push qiling (`main` yoki `master`).
2. **Actions → Build APK** ishga tushadi (yoki *Run workflow*).
3. Tugagach: run sahifasi → **Artifacts → DublajUz-debug-apk** (zip) → ichidagi APK'ni telefonga o'rnating.
4. `v0.3.0` kabi tag push qilsangiz, APK **Releases** sahifasiga ham chiqadi (telefondan to'g'ridan-to'g'ri yuklab olish oson).

CI repodagi `gradle-wrapper.jar` ni ishlatmaydi — rasmiy Gradle 8.9 o'rnatadi.

Ixtiyoriy (o'z kalitingiz bilan imzolash): repo *Secrets*ga `KEYSTORE_B64` (base64 keystore),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` qo'shing. Bo'lmasa release APK debug kalit bilan imzolanadi.

## Birinchi ishga tushirish (telefonda)
**Sozlamalar** ekranida:
- **Whisper**: model tanlang (base tavsiya) → *Yuklash* (yoki `ggml-*.bin` ni import).
  Bepul AAR faqat oddiy (kvantlanmagan) modellarni qo'llaydi.
- **NLLB**: *Yuklash* (~1 GB) yoki Xenova/nllb-200-distilled-600M papkasini *Papkadan import*.
- **VITS**: `vits_uzbek.onnx` va `uz_tts_base.json` ni import qiling.
- **Video tili**: Ru/En dan boshqa tillar uchun tilni aniq tanlang.

So'ng: video tanlang → *Transkript + tarjima* → tahrirlang → *Tasdiqlash* → *Dublyaj qilish*.
Natijani *Ko'rish / Ulashish / Galereyaga saqlash* mumkin.

## Cheklovlar
- Faqat **arm64-v8a** (Whisper AAR bepul versiyasi).
- Video MP4 uchun mos kodekda bo'lishi kerak (H.264/H.265).
- **NLLB litsenziyasi CC-BY-NC** — tijoriy ilovada ishlatib bo'lmaydi.
- Kamida ~4 GB RAM tavsiya etiladi (NLLB ~1 GB).

## Versiya
0.3.0-alpha
