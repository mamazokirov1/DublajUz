"""
DublajUz uchun Chatterbox (o'zbekcha fine-tune) TTS serveri.

Bu fayl `gokhaneraslan/chatterbox-finetuning` papkasining ildizida (inference.py yonida) ishga tushiriladi:
    CB_TOKEN=maxfiy_token uvicorn chatterbox_server:app --host 0.0.0.0 --port 8000

Endpointlar:
    GET  /health            -> {"ok": true, "device": "...", "sr": 24000}
    POST /tts               -> audio/wav (PCM16)
         form: text, exaggeration (ixtiyoriy), cfg_weight (ixtiyoriy), seed (ixtiyoriy)
         file: ref (ixtiyoriy) — klonlanadigan namuna ovoz; bo'lmasa standart ovoz
Barcha so'rovlarda `X-Api-Key: <CB_TOKEN>` sarlavhasi kerak.
"""
import hashlib
import io
import os
import secrets
import tempfile
import threading
from typing import Optional

import soundfile as sf
import torch
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response

import inference as ci  # model repo'dagi inference.py (toolkit ildizida turishi kerak)

TOKEN = os.environ.get("CB_TOKEN") or secrets.token_urlsafe(16)
BASE = os.environ.get("CB_BASE", "pretrained_models")
WEIGHTS = os.environ.get("CB_WEIGHTS", "uzbek_chatterbox/t3_finetuned_merged.safetensors")
DEFAULT_REF = os.environ.get("CB_DEFAULT_REF", "uzbek_chatterbox/reference_voice.wav")
MAX_REF_BYTES = 25 * 1024 * 1024

device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"[server] model yuklanmoqda ({device}) ...", flush=True)
engine = ci.load_engine(BASE, WEIGHTS, device)
print("[server] tayyor. Token:", TOKEN, flush=True)

lock = threading.Lock()  # GPU'da bir vaqtda bitta sintez
ref_dir = tempfile.mkdtemp(prefix="cb_refs_")
app = FastAPI()


def _auth(key: Optional[str]):
    if not key or not secrets.compare_digest(key, TOKEN):
        raise HTTPException(status_code=401, detail="bad token")


@app.get("/health")
def health(x_api_key: Optional[str] = Header(default=None)):
    _auth(x_api_key)
    return JSONResponse({"ok": True, "device": device, "sr": int(engine.sr)})


@app.post("/tts")
def tts(
    text: str = Form(...),
    exaggeration: float = Form(0.5),
    cfg_weight: float = Form(0.5),
    seed: int = Form(42),
    ref: Optional[UploadFile] = File(None),
    x_api_key: Optional[str] = Header(default=None),
):
    _auth(x_api_key)
    text = (text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text bo'sh")

    ref_path = DEFAULT_REF
    if ref is not None:
        data = ref.file.read(MAX_REF_BYTES + 1)
        if len(data) > MAX_REF_BYTES:
            raise HTTPException(status_code=413, detail="ref juda katta")
        if data:
            digest = hashlib.sha1(data).hexdigest()
            suffix = os.path.splitext(ref.filename or "")[1][:6] or ".wav"
            ref_path = os.path.join(ref_dir, digest + suffix)
            if not os.path.exists(ref_path):
                with open(ref_path, "wb") as f:
                    f.write(data)

    with lock:
        try:
            audio, sr = ci.synthesize(
                engine, text, ref_path,
                exaggeration=exaggeration, cfg_weight=cfg_weight,
                repetition_penalty=2.0, temperature=0.8, seed=seed,
            )
        except Exception as e:  # noqa: BLE001
            raise HTTPException(status_code=500, detail=f"sintez xatosi: {e}")

    if audio is None or len(audio) < 100:
        raise HTTPException(status_code=500, detail="bo'sh audio")

    buf = io.BytesIO()
    sf.write(buf, audio, sr, format="WAV", subtype="PCM_16")
    return Response(content=buf.getvalue(), media_type="audio/wav")
