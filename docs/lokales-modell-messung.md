# Messung: lokales KI-Modell auf dem Galaxy S21 FE (10.10.2026)

Gerät: SM-G990B, Snapdragon 888, 6 GB RAM, Android 16. llama.cpp (Build 781dbc5), arm64, 4 Threads, Q4_K_M.

| Modell | Prompt-Verarbeitung | Textgenerierung | Realer Lauf, 103-Token-Prompt |
|---|---|---|---|
| Qwen2.5-1.5B-Instruct | 84,8 ± 1,7 Tok/s | 24,4 ± 0,4 Tok/s | 1,45 s Prompt + 2,53 s für 57 Token; Extraktion korrekt |
| Qwen2.5-3B-Instruct | 37,4 ± 1,2 Tok/s | 11,4 ± 0,6 Tok/s | 16,5 s Prompt + 277 s für 49 Token (RAM-Druck neben Jarvis/System) |

Bewertung: Ein vollständiger Jarvis-Prompt (Persönlichkeit, Werkzeuge, Verlauf) hat ca. 1 500 Token → beim 1,5B-Modell ca. 18 s bis zum ersten Wort; Werkzeugaufrufe bei dieser Größe unzuverlässig. Das 3B-Modell ist mit 6 GB RAM im Dauerbetrieb nicht stabil. Entscheidung: Groq Free als Sprachmodell; der Schulablauf funktioniert bei KI-Ausfall regelbasiert weiter (Selbsttest 6c).
