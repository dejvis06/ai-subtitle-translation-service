# AI Subtitle Translation Service

## Project Overview

AI Subtitle Translation Service is a web service that accepts subtitle files in the `.srt` format and returns a fully translated version in any target language of your choice, powered by AI.

You upload a subtitle file and specify the target language. The service reads through every subtitle block, extracts the spoken text, sends it to an AI model for translation in batches, and stitches the translated lines back into a properly formatted `.srt` file — preserving timestamps, subtitle numbers, and the overall structure exactly as they were. The translated file is returned as a download, named after the original file with the target language appended.

---

## Project Structure

```
ai-subtitle-translation-service/
├── backend/          ← Spring Boot service (Java 25, Spring AI, Jetty)
│   ├── src/
│   ├── pom.xml
│   └── mvnw
│
├── ui/               ← Static frontend (no framework)
│   ├── index.html
│   ├── style.css
│   └── app.js
│
└── README.md
```

---

## UI

The frontend is a self-contained static site in the `ui/` directory — no build step or framework required.

Open `ui/index.html` directly in a browser (or serve it with any static file server) while the backend is running.

**Features:**
- Drag & drop or browse for an `.srt` file
- Language selector with search (currently supports Albanian)
- Live progress bar updated via SSE as each translation batch completes
- Auto-downloads the translated file when done, with a manual fallback button

---

## Backend

The service lives in `backend/`. It is a Spring Boot application using Spring AI for structured AI translation output and Jetty as the embedded web server.

---

## Flow

### 1. Request

The client POSTs the `.srt` file and target language to `TranslationController`. The controller reads the file bytes synchronously, creates a job entry, and immediately dispatches translation to an async thread pool — returning a `jobId` without blocking.

```
POST /api/translations
Content-Type: multipart/form-data

file=<.srt file>
translateTo=Albanian

→ 202 Accepted
{ "jobId": "uuid" }
```

### 2. SSE Progress

The client subscribes to a per-job SSE stream:

```
GET /api/translations/{jobId}/events
```

The stream emits:

| Event      | Data                                                       |
|------------|------------------------------------------------------------|
| `progress` | `{ percentage, processedBatches, totalBatches }`           |
| `done`     | `{}`                                                       |
| `error`    | `{ message }`                                              |

### 3. Save to Filesystem

`TranslationApplicationService` saves the uploaded bytes to a temporary directory before processing, then deletes it when done.

### 4. SRT Parsing

The raw file content is handed to `SubtitleFile.parse()`, which walks through every subtitle block, extracts the spoken text, and replaces it with a numbered placeholder — keeping the original text in an ordered `List<TranslationEntry>`.

Given this input:

```
1
00:01:58,991 --> 00:02:00,367
Good luck.

2
00:03:39,216 --> 00:03:42,136
Hi. I have an appointment with...
```

After parsing, the in-memory content becomes:

```
1
00:01:58,991 --> 00:02:00,367
{{TRANSLATION_1}}

2
00:03:39,216 --> 00:03:42,136
{{TRANSLATION_2}}
```

And the entries list is:

```java
TranslationEntry("{{TRANSLATION_1}}", "Good luck.")
TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")
```

### 5. Batched AI Translation

`TranslationApplicationService` splits entries into batches of 20 and calls `AiTranslationClient` for each. After each batch, a progress event is pushed to the client via SSE.

```java
// Entries 1–20   → AI request 1  → SSE progress event
// Entries 21–40  → AI request 2  → SSE progress event
// ...
```

`SpringAiTranslationClient` sends the batch to the AI and maps the response directly to `List<TranslatedEntry>` via Spring AI structured output. To avoid log noise on large files, AI request/response messages are logged **once every 5 batches** (= every 100 entries).

The AI receives:

```
TranslationEntry("{{TRANSLATION_1}}", "Good luck.")
```

And returns:

```java
TranslatedEntry("{{TRANSLATION_1}}", "Paç fat.")
```

### 6. Placeholder Replacement

`SubtitleFile.applyTranslations()` replaces each placeholder in the in-memory content with the translated text.

The final file:

```
1
00:01:58,991 --> 00:02:00,367
Paç fat.

2
00:03:39,216 --> 00:03:42,136
Përshëndetje. Kam një takim me...
```

### 7. Download

When the SSE `done` event fires, the client fetches the translated file:

```
GET /api/translations/{jobId}/download
→ 200 OK  (movie.Albanian.srt as attachment)
```

The job is removed from memory after the file is retrieved.

---

## Running the Service

Set your OpenAI API key, then start the application from the `backend/` directory:

**PowerShell**
```powershell
cd backend
$env:OPENAI_API_KEY="sk-..."
./mvnw spring-boot:run
```

**CMD**
```cmd
cd backend
set OPENAI_API_KEY=sk-...
mvnw spring-boot:run
```

Once running:

- **API docs (Swagger UI):** `http://localhost:8080/swagger-ui/index.html`
- **Frontend:** open `ui/index.html` in your browser (or serve with `npx serve ui`)
