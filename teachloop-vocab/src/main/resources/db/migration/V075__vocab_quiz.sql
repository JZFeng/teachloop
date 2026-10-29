-- US-87 / T546：复习小测（练习，不是考试）。
-- 表归属：vocab。
--
-- 设计要点（spec §26 AC-87.x）：
--   * 判分必须在服务端 —— 题目与答案都不能交给浏览器，所以**生成即落库**；
--   * 不写 vocab_progress、不写 vocab_review_logs：小测不进入 SM-2，也不计入「今日已复习」；
--   * 未交卷也保留（IN_PROGRESS），它是「点开小测但没交卷」这个比例的分母，7 天后清理。

create table vocab_quizzes (
  id uuid primary key,
  user_id uuid not null,
  -- TODAY = 今日复习结束；SESSION = 自由复习结束；RETRY = 结果页「复习错题」
  kind varchar(16) not null check (kind in ('TODAY', 'SESSION', 'RETRY')),
  -- SESSION 卷的来源会话；RETRY 卷的来源卷。两者都只做弱引用，不建外键（源可被清理）
  source_session_id uuid,
  source_quiz_id uuid,
  status varchar(16) not null default 'IN_PROGRESS' check (status in ('IN_PROGRESS', 'SUBMITTED')),
  item_count int not null,
  correct_count int,
  -- 抽样种子：同一种子重建出同一套题，便于复现与排查
  seed bigint not null,
  created_at timestamptz not null,
  submitted_at timestamptz,
  -- 未交卷清理用：last_seen_at 在每次进入卷面时更新
  last_seen_at timestamptz not null
);

create index idx_vocab_quizzes_user_time on vocab_quizzes (user_id, created_at desc);
create index idx_vocab_quizzes_stale on vocab_quizzes (last_seen_at) where status = 'IN_PROGRESS';

create table vocab_quiz_items (
  id uuid primary key,
  quiz_id uuid not null references vocab_quizzes (id) on delete cascade,
  -- 冗余 user_id：review-back 的「上次小测没答对」按 (user_id, word_id) 查，避免每次都 join 卷头
  user_id uuid not null,
  word_id uuid not null,
  position int not null check (position >= 0),
  question_type varchar(16) not null check (question_type in ('SINGLE_CHOICE', 'FILL_BLANK')),
  -- 挖空后的题干（已渲染文本，内容后续变化不影响已答记录）
  stem text not null,
  -- [{"key":"A","text":"induce"}, ...]；填空题写空数组
  options jsonb not null default '[]'::jsonb,
  -- 选择题存正确选项键（"A"）；填空题存句中出现的表面词形，判分时按词族放宽
  answer_key varchar(120) not null,
  given varchar(200),
  correct boolean,
  answered_at timestamptz
);

create index idx_vocab_quiz_items_quiz on vocab_quiz_items (quiz_id, position);
-- AC-87.9：取某词「最近一次答错的卷子时间」
create index idx_vocab_quiz_items_wrong on vocab_quiz_items (user_id, word_id) where correct = false;

-- AC-88.7 / T552：背单词偏好。与 vocab_user_prefs.table_columns 分开一列：
-- 前者是「服务端不解释形状」的纯前端配置，这一列**必须被队列解释**，所以由后端校验。
alter table vocab_user_prefs add column review jsonb not null default '{}'::jsonb;
