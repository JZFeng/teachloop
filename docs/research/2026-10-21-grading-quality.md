# Research — grading quality: why the current AI review looks like a placeholder, and what actually raises quality (2026-10-21)

Purpose: ground the grading-quality redesign before changing spec/plan. Three inputs:

1. the user's manual opencode run (`teaching_english/HOMEWORK/Grading_Prompt.md`,
   `reviews/Alejandro_review.txt`, `Rubric.md`);
2. the running TeachLoop stack and the code that produced the screenshot;
3. published evidence on LLM rubric scoring, plus a local probe on the 10-submission golden set.

---

## 1. The screenshot is not an AI result — it is the stub engine

The screenshot shows `AI 建议分 7.5`, `内容要点 4.5/6`, `语言准确性 3/4`,
`stub engine: 这不是真实评分结果`.

Those numbers are produced by `StubGradingEngine` (`teachloop-grading/.../engine/StubGradingEngine.java`):

- `subjective = essayMax × 0.75 = 10 × 0.75 = 7.5`
- `内容要点 = 7.5 × 0.6 = 4.5`, max `10 × 0.6 = 6`
- `语言准确性 = 7.5 − 4.5 = 3`, max `10 − 6 = 4`

The criterion names `内容要点 / 语言准确性` are hard-coded in the stub. They are not read from
the assignment's rubric, which is the user's `Rubric.md` (`Required Length 2 / Clear Position 4 /
Explanation and Support Details 4`). Confirmed against the dev database: offering
`6eb1037b…` has that 704-character rubric and `feedback_language = zh-CN`; both reviews on it are
`7.5` with `stub：按固定比例给分` in `ai_criteria`.

Three independent reasons the real AI never ran:

1. `teachloop-app/src/main/resources/application.yml` hard-codes `teachloop.grading.engine: stub`.
2. `ai_settings.api_key_cipher` is empty in the dev database (no DeepSeek key configured).
3. Even if switched to `ai`, the AI path has no task context, no anchor, no evidence rule, and
   defaults `feedback_language = zh-CN`, so it would write Chinese comments for an English essay.

**Implication:** the first fix is truthfulness, not prompt wording. A fake score is worse than
"AI 未完成". The stub must never be presented as an AI suggestion.

---

## 2. Local probe: current prompt vs a proposed prompt

A one-off local probe (deepseek-flash, temperature 0.2, JSON mode) graded the 10 golden
submissions with:

- **A** = the current `PromptBuilder` v1 semantics (generic teacher, rubric text, criteria + max,
  JSON contract, `zh-CN` comments, no assignment context, no evidence rule);
- **B** = a proposed v2 prompt (TESOL instructor, assignment task, full rubric descriptors,
  binary-scoring rule, verbatim-evidence rule, class length policy, English feedback,
  JSON contract).

Scores (human reference in parentheses; all 10 human reviews are `2,4,4` except JZ and Mark,
which are `0,4,4`):

| Student | Human | A total / criteria | B total / criteria |
| :--- | :--- | :--- | :--- |
| Akshay | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| Alejandro | 10 | 10 / 2,4,4 | **8** / 2,4,4 (total ≠ sum) |
| Genesis | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| Iam | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| JZ | 8 | 8 / 0,4,4 | 10 / 2,4,4 (class policy count = 4) |
| Maria | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| Mark | 8 | 8 / 0,4,4 | 8 / 0,4,4 |
| Regan | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| Sana | 10 | 10 / 2,4,4 | 10 / 2,4,4 |
| Zhangguan | 10 | 10 / 2,4,4 | 10 / 2,4,4 |

What the probe actually shows:

- **On this easy binary rubric, both prompts score close to the human reference.** The quality
  problem is not mainly the total. It is that A writes generic, unquoted, Chinese comments
  (`"学生正文共4段，符合1–4段的要求。"`), while B writes English, evidence-quoted, criterion-tied
  comments (`"…states \"I am personally opposed to the assumption\" and reiterating opposition in
  the conclusion."`). A's strength/suggestion are generic; B's are specific and actionable.
- **B exposes a contract failure the current system would also hit:** Alejandro's `total` was 8
  while criteria summed to 10. The current validator would retry once and then fail the
  submission. A quality gate must reject this, not average it away.
- **Length is a policy decision, not a model judgment.** Naive blank-line counting gives Maria
  8 paragraphs (bullet list) and JZ 5 (greeting as its own paragraph); the class length policy
  gives Maria 4 and JZ 4, Mark 5. The human reference marks JZ `0/2` although the documented
  default policy puts JZ in range — the policy doc itself records this inconsistency. The AI must
  use one class-wide policy, state the count, and flag boundary cases for the teacher. It must not
  silently follow the model's own count.
- Latency was 2.8–7.3 s (A) and 4.5–11.4 s (B) for deepseek-flash; cost is negligible.

The probe was local only (`work/grading-quality/experiment.py`, outputs not committed). It is
evidence for the design, not a production benchmark: N=10, one run, one model.

---

## 3. Published evidence

### 3.1 Rubric structure dominates; prompt format is secondary; temperature is minor

Tang, Ambrose & Cheng (2026), *Designing Reliable LLM-Assisted Rubric Scoring for Constructed
Responses: Evidence from Physics Exams* — [arXiv:2604.12227](https://arxiv.org/abs/2604.12227).

- 20 handwritten physics responses, 4 instructors × 2 rounds, GPT-4o, skill-based rubrics of
  different analytic granularity, prompting and temperature varied.
- Human–AI agreement on totals was comparable to human inter-rater reliability, highest at the
  high and low ends, and declined for mid-level responses with partial or ambiguous reasoning.
- Criterion-level agreement was stronger for clearly defined conceptual skills than for extended
  procedural judgments.
- A finer-grained, checklist-based rubric improved consistency versus holistic scoring.
- Prompting format was secondary; temperature had limited impact.

**Implication:** spend the effort on rubric clarity and criterion-level judgments, not on prompt
theatre or temperature tuning. For TeachLoop, that means rubric validation and per-criterion
output are the core, and a structured rubric is worth more than a longer prose prompt.

### 3.2 Fixed criteria + traceable evidence + calibrated boundaries beat prompt-level scoring

Hong et al. (2026), *From Rubrics to Reliable Scores: Evidence-Grounded Text Evaluation with LLM
Judges* (EMNLP 2026) — [arXiv:2601.08654](https://arxiv.org/abs/2601.08654).

- Defines *criteria transfer*: a frozen model interprets the same rubric inconsistently, produces
  score attributions that are hard to audit, and maps judgments poorly onto human scales.
- `Rulers` locks a task-level rubric specification, executes structured evidence-grounded
  judgments, and calibrates to human score boundaries.
- Across four benchmarks and multiple backbones it improved agreement in most settings, matched
  score distributions better, and was more stable under semantically equivalent rubric
  perturbations.
- Ablations: gains depend on the combination of fixed criteria, traceable evidence, and calibrated
  score interpretation — not post-hoc alignment alone.

**Implication:** the output must carry per-criterion evidence that a teacher can audit, and the
system must pin the rubric/prompt version per run. A JSON score without evidence is not auditable.

### 3.3 Anchor papers are the largest single prompting gain

Choi, Tate, Ritchie, Nixon & Warschauer (2025), *Anchor Is the Key: Toward Accessible Automated
Essay Scoring with Large Language Models Through Prompting* — [EdArXiv](https://doi.org/10.35542/osf.io/cbhgz_v1),
summary at [aied/choi-anchor-aes-prompting-2025](https://github.com/edtechdev/aied/blob/main/content/en/articles/choi-anchor-aes-prompting-2025.md).

- 493 secondary history argumentative essays; human–human QWK 0.79.
- Adding anchor papers (pre-scored exemplars) lifted GPT-4o QWK to 0.72; adding source material
  lifted it only 0.06, while anchors lifted it 0.29.
- One anchor per score level reached 0.65; anchoring the extremes (1 and 6) beat spreading anchors
  across intermediate scores (0.59 vs 0.39).
- GPT-4o mini was within 0.1 QWK of GPT-4o at roughly 200× lower cost.
- Report agreement by score band: the gains and failures concentrate at the top score.

**Implication:** two or three teacher-approved exemplars (one full score, one partial/failure) are
worth more than another paragraph of instructions. They must be anonymized/consented, and chosen
for the assignment, not scraped from the class being graded.

### 3.4 Few-shot + annotation-like rubric guidance beats long rubric rewrites

Marigo, Wright, Malkin & Xie (2026), *Evaluating Multi-Phase and Granular Strategies for
Evidence-Anchored Automated Scoring* — [ACL Anthology](https://aclanthology.org/2026.aimecon-wip.3/).

- 86 middle-school geoscience responses × 11 indicators; few-shot LLMs; separate *presence*
  agreement from *extract* agreement.
- Joint prompting + annotation-like rubric guidance + examples gave the clearest gains; lengthy
  rubric rewriting and clause-level parsing did not reliably improve extract alignment.

**Implication:** keep the prompt compact; show the rubric as the teacher wrote it, plus a short
annotation guide and examples. Do not paraphrase the rubric into a long essay.

### 3.5 Human-in-the-loop review is real work, not a rubber stamp

Hermanus & Sasmoko (2026), *A Human-in-the-Loop AI Agent for Rubric-Guided Formative Feedback in
Higher Education* — [Zenodo](https://zenodo.org/records/22234876).

- Rubric retriever → feedback generator → rubric alignment checker → risk checker → lecturer review
  → audit log.
- Lecturer actions: approve / minor edit / major edit / reject; audit stores the final decision,
  comments, edits, and final feedback version.
- 5 lecturers: 40% approved, 60% needed minor edits, 0 major edit or rejection.

**Implication:** the AI panel should be a draft with provenance and per-criterion adoption, not a
one-click "accept everything" that hides what changed. Audit must keep prompt/model version and the
final decision.

---

## 4. What the manual opencode run did that TeachLoop does not

| Manual run (opencode) | TeachLoop today |
| :--- | :--- |
| Reads `Grading_Prompt.md`: role, task, context, constraints, output format | `PromptBuilder` has a generic role and a JSON contract; no assignment task, no anchors |
| Reads the rubric before grading; binary 0/full per criterion | Rubric is in the prompt, but the stub ignores it; validator accepts any points within max (partial credit allowed) |
| Uses one length-counting policy and writes the paragraph count in the reason | Naive blank-line count; the count is passed as an "objective metric" but the model can ignore it |
| Quotes evidence from the submission; judges support, not viewpoint | No evidence rule; comments can be empty; viewpoint rule absent |
| Writes English reviews for English writing | `feedback_language` defaults to `zh-CN`; assignment form does not expose it |
| Produces one review file per student + `class_summary.txt` | Per-criterion AI draft in the review page; no AI class summary |
| Validates reviews with `validate_reviews.py` | `GradeOutputValidator` checks names/order/points/total only |
| Reviewer treats AI as first pass; teacher decides | D-15 already says this, but the UI presents a fake score with "采用 AI 建议分" |

The existing `docs/rubrics/` and `teaching_english/HOMEWORK/skills/ai-batch-grading/` assets are
the reference: the four-model length policy, the boundary flags, the 3-student example, and the
10-student golden set all exist locally and should be reused rather than reinvented.

---

## 5. Design implications (to settle before spec)

1. **Truthfulness first.** No fake scores. AI unavailable → a visible "AI 未完成 / 需人工判分"
   state, and manual grading remains first-class (D-15). Stub only in a dev profile, labeled, and
   never described as an AI suggestion.
2. **Rubric is the contract.** Validate criteria, max points, descriptors, and total at authoring
   time; show the teacher exactly what the AI will see; offer a real default template instead of
   empty bullets.
3. **Criterion-level evidence.** JSON per criterion with points, max, a comment that quotes the
   submission, plus one strength and one suggestion; pin prompt/model/rubric version.
4. **Deterministic where possible.** Compute the length criterion under one class-wide policy,
   state the count, flag boundary cases; do not let the model count paragraphs differently per
   student.
5. **Anchors, consented.** Two or three teacher-approved, anonymized exemplars per assignment (one
   full score, one length failure) — the largest measured prompting gain.
6. **Language is explicit.** English writing should normally get English feedback; the setting must
   be visible per assignment, not silently inherited from a `zh-CN` default.
7. **Measure before shipping.** Golden-set evaluation per criterion (exact/within-one), total MAE,
   evidence coverage, language compliance, contract pass rate; report boundary-sensitive cases
   separately because the human reference itself contains a known inconsistency (JZ).
8. **Audit and provenance.** Keep the raw model output, prompt version, model, latency, and the
   teacher's final decision; "AI 草稿" must never look like a published grade.

---

## 6. Open questions for the user

- Scope: prompt + truthfulness + gate only, or also rubric authoring validation / AI class summary?
- Failure behavior when the key is missing or the model breaks: hard fail, or dev-only stub?
- Feedback language: per assignment, follow rubric language, follow student/UI language?
- Rubric authoring: validated Markdown, or a structured criteria editor?
- Length: code-decided class policy with teacher-confirmed boundary cases, or model judgment?
- Anchors: anonymized teacher-approved exemplars, synthetic exemplars, or none?
- Quality gate: hard release threshold on the golden set, or a manual report?
- Delivery: settle spec/plan/tasks first, or implement in this round after the answers?
