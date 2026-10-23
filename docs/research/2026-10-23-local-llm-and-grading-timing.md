# Research — local LLM configuration and grading dispatch timing (2026-10-23)

Purpose: ground four requested changes before touching spec or code:

1. make the AI model freely configurable, including local LLMs (papers are private);
2. move teacher-only settings off the shared profile page;
3. split the profile experience by role (teacher vs student);
4. make the assignment grading trigger configurable (fixed time vs due date), defaulting to
   today's hard-coded fixed time.

The current code was read first: `teachloop-identity/.../ai/AiSettingsService.java`,
`teachloop-grading/.../ai/DeepSeekLlmClient.java`,
`teachloop-grading/.../engine/AiGradingConfig.java`,
`teachloop-sitting/.../service/AssignmentGradingJob.java`,
`teachloop-sitting/.../service/OfferingGradingDispatcher.java`,
`web/src/app/ProfilePage.tsx`, and decision 003.

---

## 1. Local model servers: what "configure an arbitrary model" has to support

| System | OpenAI-compatible endpoint | Local auth | Model discovery | JSON control | Relevant fact |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Ollama | `http://localhost:11434/v1` | The local API **does not require authentication**; a client library may require a key value but Ollama ignores it. | `GET /v1/models` | JSON via `format` / prompt; OpenAI `response_format` support varies by version | Runs one small model on a laptop or a small VM; the usual "local LLM" entry point. |
| LM Studio | `http://localhost:1234/v1` | Local server needs no key by default; authentication is optional. | `GET /v1/models` | Structured Output documented for OpenAI-compatible calls | Desktop app with a one-click server; good for a teacher's own machine. |
| vLLM | `http://host:8000/v1` | No key unless the server is started with `--api-key`; then Bearer token required. | `GET /v1/models` | Guided/structured JSON supported | Production-grade serving on a GPU box; useful when several teachers share one model. |
| llama.cpp server | `http://host:8080/v1` | No key by default. | `GET /v1/models` | JSON schema / grammar constrained output | Smallest footprint; runs on CPU, which matches "papers are simple". |
| LocalAI | `http://host:8080/v1` | Optional API key. | `GET /v1/models` | JSON mode supported | Drop-in OpenAI replacement with model backends beyond llama.cpp. |
| Jan | `http://localhost:1337/v1` | Local API; no cloud key required. | `GET /v1/models` | Prompt-driven JSON | Desktop alternative to LM Studio; same "local first" posture. |

Common shape: **base URL + model name are the two required fields; the API key is optional and
often blank for local servers.** A client that hard-fails on a blank key cannot use any of the six.

Sources:

- Ollama authentication — <https://docs.ollama.com/api/authentication>
- Ollama OpenAI compatibility — <https://docs.ollama.com/api/openai-compatibility>
- LM Studio OpenAI compatibility — <https://lmstudio.ai/docs/developer/openai-compat>
- vLLM OpenAI-compatible server — <https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html>
- llama.cpp server — <https://github.com/ggml-org/llama.cpp/tree/master/tools/server>
- LocalAI — <https://localai.io/basics/getting_started/>
- Jan local API — <https://jan.ai/docs/desktop/api-server>

---

## 2. Grading timing: due date is not the same decision as "when feedback is ready"

| System | Due date | When grading/release happens | What it teaches |
| :--- | :--- | :--- | :--- |
| Turnitin | Per assignment | A separate **Feedback Release Date** is set per assignment; grades and feedback become available automatically at that date/time. | "When students can see feedback" is an explicit, separate setting — not implied by the due date. |
| Canvas | Per assignment | Teacher grades in SpeedGrader; students see grades when the teacher posts them. No automatic AI batch. | The platform does not assume "due date = grading starts"; the teacher controls the moment. |
| Gradescope | Per assignment | Instructor publishes grades; regrade requests open after publishing. | Publishing is a deliberate teacher action; due date only closes submissions. |
| Google Classroom | Per assignment | Teacher returns grades manually; returning is separate from the due date. | Same separation: submission deadline ≠ feedback timing. |
| Moodle | Due date and cutoff date | Scheduled tasks (cron) handle reminders and background work; grading is teacher-driven. | A scheduled background job is a normal pattern, but it is infrastructure-level, not a per-teacher promise. |
| Crowdmark | Per assignment | Grading workflow runs after submissions; release is controlled by the instructor. | Batch grading is compatible with a teacher-controlled release. |

Implication: the user's request is coherent with how mature products model the problem. The due
date closes submissions; **when the AI draft is produced is a separate policy**. Today TeachLoop
hard-codes one global policy (02:30 Asia/Shanghai for every assignment) and has no teacher-level
handle. The existing manual "立即派发" remains the escape hatch.

Sources:

- Turnitin Feedback Release Date — <https://lead.mic.ul.ie/understanding-turnitin-settings/turnitin-post-date>
- Canvas SpeedGrader — <https://community.canvaslms.com/t5/Instructor-Guide/How-do-I-use-SpeedGrader/ta-p/560>
- Gradescope publishing grades — <https://guides.gradescope.com/hc/en-us/articles/22218794730901-Publishing-Grades>
- Google Classroom return grades — <https://support.google.com/edu/classroom/answer/6020297>
- Moodle assignment settings — <https://docs.moodle.org/en/Assignment_settings>
- Crowdmark — <https://crowdmark.com/>

---

## 3. Current TeachLoop gaps, confirmed in code

| Gap | Evidence | Consequence |
| :--- | :--- | :--- |
| Blank key is treated as "not configured" | `AiSettingsService.resolve` only returns a user setting when `configured()` is true; `configured` is derived from a non-blank key. `Ports.AiSettings.configured` is set from `key != null && !key.isBlank()`. | A local Ollama/LM Studio endpoint with no key is skipped, falls back to the global config, and finally fails with "NO_KEY". |
| Clients refuse to call without a key | `DeepSeekLlmClient.complete` throws when `apiKey.isBlank()`; `SpringAiLlmClient.complete` does the same. | Even if the setting were saved, the call never reaches the local server. |
| Setup forces a key | `SetupEndpoint.createAdmin` rejects the first admin when no key is supplied and no global config exists. | A local-only deployment cannot finish setup without inventing a fake key. |
| Model defaults are baked into code and UI | `deepseek-flash` in `AiSettingsService`, `application.yml`, `AdminAiPage`, `ProfilePage`, `ReviewPage`; `provider` is only a label. | "Configurable" is true only after a teacher finds the field; the default path still reads as DeepSeek-only. |
| One global nightly cron | `AssignmentGradingJob` uses `@Scheduled(cron = "${teachloop.grading.assignment-cron:0 30 2 * * *}")` and `dispatchAssignments()` dispatches every teacher's assignments. | No per-teacher time, no per-teacher due-date mode, and no state to stop a minute-level scanner from re-dispatching after the first run. |
| Assignment due-date path is disabled | `OfferingRepository.findDueForGrading` filters `kind = 'EXAM'`; `findAssignmentsWithQueued` ignores due dates. | Switching an assignment back to due-date behaviour has no query to call today. |
| Profile page is shared | `web/src/app/ProfilePage.tsx` renders account fields plus AI settings for TEACHER/ADMIN; students see the same page without the AI card. | The page mixes "personal account" and "teaching configuration"; the user wants the teacher-only block on a teacher page. |

---

## 4. Design implications carried into the decision survey

1. **Key optional, not key required.** A setting is usable when `baseUrl` and `model` are non-blank.
   A blank key must produce a request with no `Authorization` header (JDK client) or a placeholder
   accepted by the OpenAI SDK (Spring AI client), never a client-side "NO_KEY" failure.
2. **Provider presets are UX, not a hard enum.** Ollama / LM Studio / vLLM / DeepSeek / custom all
   speak the same `POST {baseUrl}/chat/completions`; the provider field only prefills base URL,
   model placeholder and key expectations.
3. **Model discovery is worth a button, not a requirement.** `GET {baseUrl}/models` is universal
   across the six servers, but a free-text model field must remain for servers that disable it.
4. **Teacher-level timing needs state.** A minute-level scanner must know the last fixed-time run
   per teacher (or per teacher + local date), otherwise every queued submission after 02:30 is
   dispatched immediately and the "nightly batch" promise disappears.
5. **Due-date mode should reuse the exam policy.** The pre-003 behaviour was due + grace, with the
   peak-hours guard; reusing it keeps one explanation for exams and assignments.
6. **Role split is a layout decision, not a security boundary.** The backend already returns 403
   for students on `/api/profile/ai-settings`; the remaining work is to stop showing the teacher
   block to students and to give the teacher settings a clear home.
