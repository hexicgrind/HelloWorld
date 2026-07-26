# Sotto — install and first run

*Your networking wingman, in a whisper.*

Everything below assumes you're starting from nothing. It takes about fifteen minutes,
and most of that is waiting for Gemini to read your attendee list.

---

## 1. Put the app on your phone

**Which file do I want?**

| File | Use it when |
|---|---|
| `Sotto-1.0.1-arm64.apk` | **Start here.** Every Android phone made in the last several years. 47 MB. |
| `Sotto-1.0.1-universal.apk` | If the arm64 one refuses to install. Works everywhere. 58 MB. |
| `Sotto-1.0.1-arm32.apk` | Only for a genuinely old 32-bit phone. |

**To install:**

1. Download the APK to your phone (from Drive, or copy it over USB).
2. Open your Files app and tap the APK.
3. Android will say it can't install apps from this source. Tap **Settings** on that
   prompt, turn on **Allow from this source**, then go back and tap **Install**.
4. You'll get a "scan for harmful apps?" prompt from Play Protect. This is normal for
   any app not installed from the Play Store — Sotto is signed with a self-signed
   certificate. Tap **Install anyway**.

The app is signed and will update in place if you install a newer build later.

---

## 2. Get a Google API key

Sotto uses three Google APIs. **One key covers all three**, and you only have to do
this once.

1. Go to **https://aistudio.google.com/app/apikey** and sign in.
2. Tap **Create API key**. Choose an existing Google Cloud project, or let it make one.
3. Copy the key — it starts with `AIza…`.

That key already works for Gemini. For the voice and transcription to work too, enable
two more APIs on the *same* Google Cloud project:

4. Go to **https://console.cloud.google.com/apis/library**, make sure the project
   selector at the top shows the project your key belongs to, then search for and
   **Enable** each of:
   - **Cloud Text-to-Speech API**
   - **Cloud Speech-to-Text API**

> **Will this cost money?** Both have a free tier that comfortably covers testing around
> the house — Text-to-Speech gives you 1 million characters a month free, Speech-to-Text
> 60 minutes a month. Gemini's free tier covers the assistant. Google may ask you to
> attach a billing account to enable the Cloud APIs even while you stay inside the free
> tier. If you'd rather not, see "Running without the Cloud APIs" at the bottom — Sotto
> still works, it just gets quieter.

**Then, in Sotto:** open **Settings → API keys**, paste the key, tap **Save keys**, then
tap **Test my key**. It'll tell you straight away whether it works.

---

## 3. Load your attendee list

Tap **Attendee list** on the home screen.

Give it whatever you have — it does not need to be tidy:

- a **CSV** exported from an event platform
- the conference **PDF** programme
- a **photo** of a badge table or printed roster
- **text** pasted from an email

Then tap **Prepare conference data**. Gemini reads it and builds structured records:
name, title, company, a short bio, interests, and a location if there is one. A
hundred-person list takes two to five minutes. You can leave the screen open.

**Just testing at home?** Tap the person icon in **People** and add two or three names by
hand. That's plenty to see how it feels.

---

## 4. Enrol some faces

**This is the step people skip, and then wonder why nothing is recognised.**

Sotto can only recognise someone whose face you've enrolled. A roster gives you names;
it does not give you faces.

For each person: **People → tap their name → Enrol face**, then capture three times,
changing the angle slightly between each. Or tap the photo icon to use a picture you
already have.

The three captures get averaged into one 128-number fingerprint, stored on your phone.
The photos never leave the device.

---

## 5. Connect an earpiece

A single-ear Bluetooth earpiece is ideal — you keep one ear on the actual conversation.
Wired earbuds work too, with slightly lower latency.

Pair it in Android's Bluetooth settings as usual, then check the **Earpiece** screen in
Sotto to confirm it's picked up.

Without one, Sotto talks through the phone speaker, which rather defeats the purpose.

---

## 6. Run a session

Tap **Start live session**.

- Hold the phone naturally, around chest height, camera roughly toward the person.
- When someone is recognised, the frame turns **teal** and their name appears.
- Sotto listens, and at a natural pause it whispers **one** short line: a question to
  ask, something you have in common, or a better direction.
- Every whisper also appears on screen, so you can read one you missed. Tap the replay
  arrow to hear it again.
- The subtitles button shows a running transcript.

**Silence is normal.** Sotto is built to stay quiet unless it has something specific and
useful. If it talked constantly it would be worse than nothing.

---

## Trying it around the house

The fastest way to see the whole thing work:

1. Add yourself and one other person as attendees, with real titles and interests.
2. Enrol both faces.
3. Start a session, point the phone at that person, and have a normal two-minute chat
   about work.

You'll see the teal recognition, hear the match chime, and get a whisper or two at the
first real pause. To force one sooner: **Settings → How Sotto behaves →** drop *How
often it may speak* to 5 seconds and *How long a pause must be* to 0.5 seconds.

---

## If something isn't working

| What you see | What's going on |
|---|---|
| "Sotto can't load its on-device model" | You're on 1.0.0. Install 1.0.1 or later — 1.0.0's face libraries can't load on phones with 16 KB memory pages (Android 16 and most new hardware). |
| Sotto never speaks | Check the **Assistant** dot at the top of the live screen. Amber means the Gemini key was rejected — test it in Settings. |
| Nobody is recognised | Only enrolled people can be. Check **People** for the "Recognisable" tag. |
| Wrong person recognised | **Settings → Face recognition →** raise *How sure Sotto must be*, or re-enrol both people in better light. |
| Whispers come from the phone speaker | The earpiece isn't connected. Check the **Earpiece** screen. |
| Names appear but there's no voice | Cloud Text-to-Speech isn't enabled on your project, or isn't covered by your key. |
| The transcript stays empty | Cloud Speech-to-Text isn't enabled. The assistant still works — it hears the raw audio directly. |
| It talks too much | **Settings → How Sotto behaves →** raise the interval or lengthen the required pause. |

### Running without the Cloud APIs

If you only want to use the free Gemini key and skip the two Cloud APIs:

**Settings → Advanced →** turn off *Transcribe with Google Cloud* and *Speak with Google
Cloud voices*.

Sotto then reads its own transcript from Gemini and shows whispers on screen instead of
speaking them. Recognition and suggestions still work fully.

---

## Where your data goes

- **Camera frames never leave the phone.** Face detection and matching are entirely
  on-device.
- The attendee list, face fingerprints and enrolment photos are stored in Sotto's
  private storage and are excluded from cloud backup.
- Your API key is encrypted with your phone's hardware keystore, is never logged, and is
  only ever sent to the Google endpoint it belongs to.
- While a session is running, microphone audio goes to Google Speech-to-Text and the
  Gemini Live API. Nowhere else, and nothing is retained after the session ends.
- To wipe everything: **People → Remove** each person, and **Settings → Remove all keys**.
  Or just uninstall.

## A word about the people around you

Sotto listens to whoever is nearby and recognises faces you've enrolled. Depending on
where you are, recording or identifying people may require their consent — sometimes as
a matter of law. Use it with the care you'd want someone to use around you.
