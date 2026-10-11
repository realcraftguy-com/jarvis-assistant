#!/usr/bin/env python3
"""Pronunciation check for the Supertonic voice: synthesise text variants (same models and settings as the
call app) and transcribe them with Whisper, to see what a listener actually hears.

  python tools/tts_check.py M5 "Der Mathe-Leistungsnachweis" "Der Mathe Leistungsnachweis"
"""
import json, sys, unicodedata, re
from pathlib import Path
import numpy as np, onnxruntime as ort, soundfile as sf

D = Path.home() / ".cache/supertonic3"


def load():
    cfg = json.loads((D / "onnx/tts.json").read_text())
    s = {n: ort.InferenceSession(str(D / f"onnx/{n}.onnx"), providers=["CPUExecutionProvider"])
         for n in ("duration_predictor", "text_encoder", "vector_estimator", "vocoder")}
    return cfg, s, json.loads((D / "onnx/unicode_indexer.json").read_text())


def style(v):
    j = json.loads((D / f"voice_styles/{v}.json").read_text())
    f = lambda o: np.array(o["data"], dtype=np.float32).reshape(o["dims"])
    return f(j["style_dp"]), f(j["style_ttl"])


LEXICON = [(r"(?i)\bmathe(?!matik)", "Matte"), (r"(?i)\bedupage\b", "Edu-Pehdsch")]  # same as NeuralVoice.java


def synth(cfg, s, idx, v, text, steps=6, speed=1.05, seed=1):
    for a, b in LEXICON:
        text = re.sub(a, b, text)
    text = unicodedata.normalize(__import__("os").environ.get("NORM","NFKD"), text)
    text = re.sub(r"[–—‑]", "-", text); text = re.sub(r"[_|/\[\]#*]", " ", text); text = re.sub(r"\s+", " ", text).strip()
    if not re.search(r"[.!?;:…]$", text): text += "."
    text = f"<de>{text}</de>"
    ids = np.array([[idx[ord(c)] for c in text if ord(c) < len(idx) and idx[ord(c)] >= 0]], dtype=np.int64)
    mask = np.ones((1, 1, ids.shape[1]), dtype=np.float32)
    sdp, sttl = style(v)
    dur = s["duration_predictor"].run(None, {"text_ids": ids, "style_dp": sdp, "text_mask": mask})[0].reshape(-1)[0] / speed
    emb = s["text_encoder"].run(None, {"text_ids": ids, "style_ttl": sttl, "text_mask": mask})[0]
    sr = cfg["ae"]["sample_rate"]; cc = cfg["ttl"]["chunk_compress_factor"]
    chunk = cfg["ae"]["base_chunk_size"] * cc; ld = cfg["ttl"]["latent_dim"] * cc
    n = int(dur * sr); L = (n + chunk - 1) // chunk
    lat = np.random.default_rng(seed).standard_normal((1, ld, L)).astype(np.float32)
    lm = np.ones((1, 1, L), dtype=np.float32)
    for st in range(steps):
        lat = s["vector_estimator"].run(None, {"noisy_latent": lat, "text_emb": emb, "style_ttl": sttl, "latent_mask": lm,
                                               "text_mask": mask, "current_step": np.array([st], np.float32),
                                               "total_step": np.array([steps], np.float32)})[0]
    return s["vocoder"].run(None, {"latent": lat})[0][0][:n], sr


if __name__ == "__main__":
    from faster_whisper import WhisperModel
    voice, texts = sys.argv[1], sys.argv[2:]
    cfg, s, idx = load()
    asr = WhisperModel("large-v3-turbo", device="cpu", compute_type="int8")
    import tempfile
    out = Path(tempfile.gettempdir()) / "jarvis-tts"
    out.mkdir(parents=True, exist_ok=True)
    for i, t in enumerate(texts):
        heard = []
        for seed in (1, 2, 3):
            a, sr = synth(cfg, s, idx, voice, t, seed=seed)
            f = out / f"{voice}_{i}_{seed}.wav"; sf.write(f, a, sr)
            x = np.interp(np.arange(0, len(a), sr / 16000), np.arange(len(a)), a).astype(np.float32)
            segs, _ = asr.transcribe(x, language="de", beam_size=5)
            heard.append(" ".join(x.text.strip() for x in segs))
        print(f"TEXT: {t}\n  gehört: " + " | ".join(heard))
