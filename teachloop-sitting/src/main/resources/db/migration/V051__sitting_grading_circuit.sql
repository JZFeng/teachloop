-- 决策 013（D-31）：熔断恢复后自动重派的依据。
-- grading_failed_at：失败时刻；grading_circuit_requeues：自动重派次数（上限 1）。
alter table submissions add column grading_failed_at timestamptz;
alter table submissions add column grading_circuit_requeues int not null default 0;
