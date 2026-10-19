# Research digest — AI batch-grading web system (2026-10-18)

Purpose: ground the spec and the decision survey in systems that already do this, before
locking structure. Sources fetched and read in this session.

---

## 1. Methodology: GitHub Spec Kit (SDD artifact chain)

Source: [Examine GitHub Spec Kit Commands and Results — Microsoft Learn](https://learn.microsoft.com/sl-si/training/modules/spec-driven-development-github-spec-kit-greenfield-intro/8-examine-github-spec-kit-commands-results)

The artifact chain, in strict order — each step consumes the previous output:

| Order | Command | Output |
| :--- | :--- | :--- |
| 1 | `specify init` | project structure (`.specify/`, `specs/`) |
| 2 | `/speckit.constitution` | `constitution.md` — project-wide principles |
| 3 | `/speckit.specify` | `spec.md` — user stories, acceptance criteria, FR/NFR, edge cases |
| 4 | `/speckit.plan` | `plan.md` — architecture, stack, implementation sequence |
| 5 | `/speckit.tasks` | `tasks.md` — phase-organized discrete work items |
| 6 | `/speckit.implement` | source code |

Optional gates worth adopting here: `/speckit.clarify` (surface ambiguities as questions
*before* planning) and `/speckit.analyze` (cross-artifact consistency: does every spec
requirement have a task?).

**Adopted for this project:**
- `constitution.md` = non-negotiables (teacher authority, auditability, no-0-for-unreadable).
- `spec.md` = user stories + acceptance criteria.
- `plan.md` = architecture.
- `tasks.md` = dependency-ordered checklist.
- The clarify step is being run **now** as a survey, before any spec is written.

---

## 2. Closest academic comparable: HITL rubric-guided feedback agent

Source: [Supporting Data and Reproducibility Artifacts for "A Human-in-the-Loop AI Agent for Rubric-Guided Formative Feedback in Higher Education" — Zenodo](https://zenodo.org/records/22234876) (Hermanus & Sasmoko, Sept 2026, CC-BY-4.0)

A Streamlit prototype with six components:
**Rubric Retriever → Feedback Generator Agent → Rubric Alignment Checker → Risk Checker →
Lecturer Review Module → Audit Logger.**

Concrete findings worth designing around:
- Feedback is treated as a **draft**, never a final instructional decision.
- Lecturer action set is exactly four: **approve / minor edit / major edit / reject**.
- The **audit log** stores final decision, lecturer comments, edits, and the final feedback
  version. Auditability is listed as a *core requirement*, not a nice-to-have.
- Their preliminary evaluation with 5 lecturers: **40% approved, 60% needed minor edits,
  0 needed major edit or rejection** — i.e. the review step is real work but not rework.
- Four design principles: rubric-first feedback, explicit lecturer authority, combined
  alignment **and risk** checking, auditability.
- Syntactic detail that matters: they used **synthetic submissions** during early testing
  specifically to avoid exposing real student data.
- Rubric retriever scored Hit Rate@1 0.85 / Recall@3 0.95 on 20 test queries.

**Implication:** the review/approval state machine and the audit log should be in v1, not
deferred. A one-click "accept all" is not a substitute for per-student decisions.

---

## 3. Commercial comparables

| System | Pattern observed | Source |
| :--- | :--- | :--- |
| **CoGrader** | "Grade papers free with your rubric"; AI grading + feedback aimed at teachers, rubric-driven | [cograder.com](https://cograder.com/ai-grading-feedback/) |
| **EssayGrader** | Assignment-setup-first flow; teacher configures an assignment before uploading work | [help.essaygrader.ai](https://help.essaygrader.ai/en-us/article/setting-up-your-first-assignment-1166f7/) |
| **Class Companion** | AI grading + tutoring for writing; teacher-facing workflow | [techlearning.com](https://www.techlearning.com/how-to/class-companion-teaching-with-the-ai-grading-and-tutor-tool) |
| **Gradescope** | Rubric-based batch grading at course scale; AI-assisted grading; long-standing LMS-adjacent | [UMBC PIVOT](https://pivot.umbc.edu/news/post/161079/), [gradescope-mcp](https://github.com/yuanpeng-li/gradescope-mcp) |
| **MagicSchool vs CoGrader** | Direct comparison market; feature parity is high, differentiation is workflow fit | [edugenius.app](https://www.edugenius.app/blog/magicschool-ai-vs-cograder-which-is-better-for-teachers) |

Common shape across all of them: **assignment → rubric → submissions → batch run →
per-student result → teacher review → export**. None of them make the teacher optional.

---

## 4. Local assets this system should not reimplement

Already built and tested in this workspace:

- `HOMEWORK/skills/ai-batch-grading/SKILL.md` — the full grading procedure.
- `scripts/collect_submissions.py` — inventory + **four paragraph-counting models** +
  boundary-sensitivity flagging + manifest JSON. Handles txt/docx/pdf/video/audio.
- `scripts/validate_reviews.py` — validates review format, binary scoring, arithmetic,
  coverage gaps, and class-summary consistency; exits non-zero on failure.
- `references/LENGTH_COUNTING_POLICY.md` — documents the four models and why
  boundary-sensitive length scores must be escalated to the teacher.
- `assets/example/` — a verified 3-student golden run.

**Implication:** the web system's job is orchestration + review UX + persistence, not
re-deriving grading logic. The two Python scripts are proven and should be called, not
rewritten in TypeScript.

---

## 5. Integration seam verified in this session

```
$ dsh headless --json "Reply with exactly the word: PONG"
{"type":"session","sessionId":"session-b6291c4e-...","cwd":"/private/tmp"}
{"type":"status","phase":"turn_start","turn":1}
{"type":"status","phase":"step_start","turn":1,"step":1}
{"type":"thinking","text":""}
{"type":"text","text":"PONG"}
{"type":"status","phase":"step_end","turn":1,"step":1,"usage":{"inputTokens":5072,"outputTokens":3,...}}
{"type":"status","phase":"turn_end","turn":1,"reason":{"kind":"completed"}}
{"type":"final","text":"PONG"}
```

Flags available: `--json` (NDJSON events), `--session-id` (resume), task from argv or
stdin (`-`). This gives per-step progress events for a live UI **without** writing a
custom LLM client, and it reuses the already-tested skill.

**Implication:** a backend can spawn this per assignment, stream NDJSON to the browser over
SSE, and reuse the skill verbatim. The tradeoff: it depends on the DSH binary and its
credential store being present on the host.

---

## 6. Open questions this research does NOT settle

1. Whether v1 is single-teacher-local or multi-tenant.
2. Whether the engine is `dsh headless`, a direct provider API, or the scripts alone.
3. Whether OCR (scanned PDFs) is in scope — `75145.pdf` in this workspace is a 6-page iOS
   scan with **zero** extractable characters, so this is a real and current gap.
4. Whether the review state machine is v1 or v2.
5. Storage: SQLite+filesystem vs Postgres+object store.

These are being put to the user as a survey before `spec.md` is written.
