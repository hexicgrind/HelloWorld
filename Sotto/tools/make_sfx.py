"""Sotto sound design.

Five short, soft, non-intrusive UI cues. The whole point of the product is that it
whispers, so every cue is low-amplitude, band-limited and fades out fast -- nothing
here should ever step on the user's conversation.

Rendered as 44.1kHz mono 16-bit PCM WAV (played through SoundPool).
"""
import math
import struct
import wave
from pathlib import Path

import numpy as np

SR = 44100
OUT = Path("/home/user/HelloWorld/Sotto/app/src/main/res/raw")
OUT.mkdir(parents=True, exist_ok=True)


def adsr(n, attack=0.01, decay=0.06, sustain=0.35, release=0.25):
    """Percussive-ish envelope with a smooth (click-free) attack."""
    a = int(attack * SR)
    d = int(decay * SR)
    r = int(release * SR)
    s = max(0, n - a - d - r)
    env = np.concatenate([
        np.linspace(0.0, 1.0, a) ** 2 if a else np.array([]),
        np.linspace(1.0, sustain, d) if d else np.array([]),
        np.full(s, sustain),
        (np.linspace(1.0, 0.0, r) ** 2) * sustain if r else np.array([]),
    ])
    return np.pad(env, (0, max(0, n - len(env))))[:n]


def tone(freq, dur, amp=1.0, harmonics=(1.0, 0.28, 0.10, 0.04), detune=0.0, **env):
    n = int(dur * SR)
    t = np.arange(n) / SR
    sig = np.zeros(n)
    for i, h in enumerate(harmonics, start=1):
        sig += h * np.sin(2 * math.pi * freq * i * t)
        if detune:
            sig += h * 0.5 * np.sin(2 * math.pi * freq * i * (1 + detune) * t)
    sig /= np.max(np.abs(sig)) or 1.0
    return sig * adsr(n, **env) * amp


def noise_swell(dur, amp=1.0, centre=2600.0, q=6.0):
    """Band-passed noise -- used for the airy 'breath' layer."""
    n = int(dur * SR)
    rng = np.random.default_rng(20260725)
    x = rng.normal(0, 1, n)
    # One-pole state-variable band-pass, cheap and good enough for a texture layer.
    f = 2 * math.sin(math.pi * centre / SR)
    damp = 1.0 / q
    low = band = 0.0
    out = np.empty(n)
    for i in range(n):
        low += f * band
        high = x[i] - low - damp * band
        band += f * high
        out[i] = band
    out /= np.max(np.abs(out)) or 1.0
    return out * adsr(n, attack=0.04, decay=0.10, sustain=0.5, release=0.45) * amp


def mix(*layers):
    n = max(len(l) for l in layers)
    buf = np.zeros(n)
    for l in layers:
        buf[: len(l)] += l
    peak = np.max(np.abs(buf))
    if peak > 0:
        buf = buf / peak * 0.82  # leave headroom, never clip
    # 4ms fade in/out guarantees no DC click on any decoder.
    fade = int(0.004 * SR)
    buf[:fade] *= np.linspace(0, 1, fade)
    buf[-fade:] *= np.linspace(1, 0, fade)
    return buf


def delay(sig, seconds):
    return np.concatenate([np.zeros(int(seconds * SR)), sig])


def write(name, buf, gain=1.0):
    data = np.clip(buf * gain, -1.0, 1.0)
    pcm = (data * 32767).astype("<i2")
    path = OUT / f"{name}.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"{path.name:24s} {len(pcm)/SR:.2f}s  {path.stat().st_size/1024:6.1f} KB")


# --- The cue set -------------------------------------------------------------
# Tuned to a soft Fmaj9 world so the cues never sound like an alarm.
F4, A4, C5, E5, G5, C6 = 349.23, 440.00, 523.25, 659.26, 783.99, 1046.50

# 1. Attendee matched: gentle rising two-note "recognition" chime.
write("sfx_match", mix(
    tone(C5, 0.55, 0.75, release=0.40),
    delay(tone(G5, 0.60, 0.55, harmonics=(1.0, 0.18, 0.06), release=0.48), 0.085),
    delay(tone(C6, 0.50, 0.22, harmonics=(1.0, 0.10), release=0.42), 0.17),
    noise_swell(0.42, 0.055, centre=4200),
))

# 2. Incoming whisper: one barely-there tick so the user knows a suggestion is
#    starting, without announcing it to the room.
write("sfx_whisper", mix(
    tone(E5, 0.30, 0.42, harmonics=(1.0, 0.12), attack=0.006, decay=0.05,
         sustain=0.22, release=0.22),
    noise_swell(0.26, 0.075, centre=5200, q=3.0),
), gain=0.72)

# 3. Session armed and listening: warm, confident ascending triad.
write("sfx_ready", mix(
    tone(F4, 0.70, 0.55, release=0.50),
    delay(tone(A4, 0.66, 0.45, release=0.48), 0.075),
    delay(tone(C5, 0.72, 0.40, release=0.52), 0.150),
    delay(tone(E5, 0.68, 0.20, harmonics=(1.0, 0.10), release=0.50), 0.225),
    noise_swell(0.55, 0.05, centre=3200),
))

# 4. Session ended: the same triad, descending and darker.
write("sfx_stop", mix(
    tone(C5, 0.55, 0.45, release=0.42),
    delay(tone(A4, 0.58, 0.42, release=0.44), 0.08),
    delay(tone(F4, 0.75, 0.50, release=0.60), 0.16),
))

# 5. Something needs attention: soft low double-thud, never shrill.
write("sfx_alert", mix(
    tone(233.08, 0.34, 0.60, harmonics=(1.0, 0.30, 0.08), attack=0.008,
         decay=0.08, sustain=0.25, release=0.22),
    delay(tone(196.00, 0.44, 0.55, harmonics=(1.0, 0.26, 0.06), attack=0.008,
               decay=0.09, sustain=0.25, release=0.30), 0.155),
), gain=0.8)
