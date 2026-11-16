-- CMP-260909-2152: シフト予算の月次締め後訂正。
-- 原仕訳（SHIFT_BUDGET_MONTHLY）・元の消化行は書き換えず、訂正は追記専用の別表と差額仕訳（SHIFT_BUDGET_CORRECTION）で持つ。
--   (a) budget_monthly_close_records       : 締めた事実（元額・元件数・締め操作者・現在の訂正後合計）。割当×月で1行
--   (b) budget_monthly_close_corrections   : 訂正・追加・取消の履歴（追記専用。UPDATE/DELETE の経路を作らない）
--   (c) shift_budget_monthly_close_events  : 締めイベント（締め TX 内で shiftbudget が自ドメイン表として INSERT する。
--                                            締め操作者の正本。再公開スキップ・割当削除拒否の判定にも使う）
-- クロスドメイン FK は張らない（budget_transactions へは budget 同一ドメインの FK のみ）。

-- ─────────────────────────────────────────────
-- (c) shiftbudget: 締めイベント
-- ─────────────────────────────────────────────
CREATE TABLE shift_budget_monthly_close_events (
    id BINARY(16) NOT NULL,
    allocation_id BIGINT UNSIGNED NOT NULL COMMENT '締めた割当',
    target_month VARCHAR(7) NOT NULL COMMENT '締め対象月 yyyy-MM',
    actor_kind VARCHAR(10) NOT NULL COMMENT 'MANUAL=手動 / BATCH=cron / UNKNOWN=本表導入前の締め',
    actor_user_id BIGINT UNSIGNED NULL COMMENT '手動締めの操作者。BATCH と UNKNOWN は NULL',
    closed_at DATETIME NOT NULL COMMENT '締めた日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sbmce_allocation_month (allocation_id, target_month),
    CONSTRAINT chk_sbmce_actor_kind CHECK (actor_kind IN ('MANUAL', 'BATCH', 'UNKNOWN')),
    CONSTRAINT fk_sbmce_allocation FOREIGN KEY (allocation_id) REFERENCES shift_budget_allocations (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='シフト予算 月次締めイベント（CMP-260909-2152）';

-- ─────────────────────────────────────────────
-- (a) budget: 締め記録
-- ─────────────────────────────────────────────
CREATE TABLE budget_monthly_close_records (
    id BINARY(16) NOT NULL,
    original_transaction_id BIGINT UNSIGNED NOT NULL COMMENT '原月次仕訳（SHIFT_BUDGET_MONTHLY）',
    allocation_id BIGINT UNSIGNED NOT NULL COMMENT '割当ID（他ドメインのため FK なし）',
    target_month VARCHAR(7) NOT NULL COMMENT '締め対象月 yyyy-MM',
    original_amount DECIMAL(12,0) NOT NULL COMMENT '締めた時点の月合計（原仕訳の金額）',
    original_line_count INT NOT NULL COMMENT '締めた時点の確定明細件数',
    original_exact_total DECIMAL(14,2) NOT NULL COMMENT '締めた時点の月合計（丸め前）',
    close_actor_kind VARCHAR(10) NOT NULL COMMENT 'MANUAL / BATCH / UNKNOWN',
    close_actor_user_id BIGINT UNSIGNED NULL COMMENT '手動締めの操作者。割当作成者・仕訳記録者は入れない',
    original_recorded_by BIGINT UNSIGNED NOT NULL COMMENT '原仕訳の記録者の写し（締め操作者とは区別する）',
    closed_at DATETIME NOT NULL COMMENT '締めた日時',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '訂正の通し番号（楽観ロック。最新訂正の revision）',
    posted_total DECIMAL(12,0) NOT NULL COMMENT '訂正後の月合計（仕訳ベース）',
    exact_total DECIMAL(14,2) NOT NULL COMMENT '訂正後の月合計（丸め前）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bmcr_original_transaction (original_transaction_id),
    UNIQUE KEY uq_bmcr_allocation_month (allocation_id, target_month),
    CONSTRAINT chk_bmcr_actor_kind CHECK (close_actor_kind IN ('MANUAL', 'BATCH', 'UNKNOWN')),
    CONSTRAINT fk_bmcr_original_transaction FOREIGN KEY (original_transaction_id)
        REFERENCES budget_transactions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='シフト予算 月次締め記録（CMP-260909-2152）';

-- ─────────────────────────────────────────────
-- (b) budget: 訂正履歴（追記専用）
-- ─────────────────────────────────────────────
CREATE TABLE budget_monthly_close_corrections (
    id BINARY(16) NOT NULL,
    close_record_id BINARY(16) NOT NULL,
    revision BIGINT NOT NULL COMMENT '締め記録内の通し番号（1 始まり）',
    kind VARCHAR(10) NOT NULL COMMENT 'MODIFY / CANCEL / ADD / REVERSAL',
    line_kind VARCHAR(10) NOT NULL COMMENT 'ORIGINAL=元の確定明細 / ADDED=締め後に追加した明細',
    consumption_id BIGINT UNSIGNED NULL COMMENT 'ORIGINAL のとき対象の消化行ID',
    added_line_id BINARY(16) NULL COMMENT 'ADDED のとき明細ID（ADD 行自身の ID）',
    reverses_correction_id BINARY(16) NULL COMMENT 'REVERSAL のとき取り消す訂正の ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '勤務者',
    work_date DATE NULL COMMENT 'ADDED の勤務日',
    slot_id BIGINT UNSIGNED NULL COMMENT 'ADDED の勤務枠（FK なし）',
    shift_id BIGINT UNSIGNED NULL COMMENT 'ADDED のシフト（FK なし）',
    before_hours DECIMAL(6,2) NOT NULL,
    before_hourly_rate DECIMAL(10,2) NULL,
    before_amount DECIMAL(12,2) NOT NULL,
    before_line_status VARCHAR(10) NOT NULL COMMENT 'ABSENT / CONFIRMED / CANCELLED',
    after_hours DECIMAL(6,2) NOT NULL,
    after_hourly_rate DECIMAL(10,2) NULL,
    after_amount DECIMAL(12,2) NOT NULL,
    after_line_status VARCHAR(10) NOT NULL COMMENT 'ABSENT / CONFIRMED / CANCELLED',
    exact_delta DECIMAL(12,2) NOT NULL COMMENT '月合計（丸め前）の増減',
    posted_delta DECIMAL(12,0) NOT NULL COMMENT '差額仕訳の金額（月合計を丸めた値の増減）',
    exact_month_total_after DECIMAL(14,2) NOT NULL COMMENT 'この訂正後の月合計（丸め前）',
    content VARCHAR(500) NULL COMMENT '訂正内容',
    reason VARCHAR(500) NOT NULL COMMENT '訂正理由',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '訂正した人',
    command_id BINARY(16) NOT NULL COMMENT '冪等キー',
    request_hash CHAR(64) NOT NULL COMMENT '要求内容のハッシュ（同 command の内容違い検出）',
    budget_transaction_id BIGINT UNSIGNED NULL COMMENT '差額仕訳（差額0 は NULL）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bmcc_close_revision (close_record_id, revision),
    UNIQUE KEY uq_bmcc_command (command_id),
    UNIQUE KEY uq_bmcc_reverses (reverses_correction_id),
    UNIQUE KEY uq_bmcc_transaction (budget_transaction_id),
    INDEX idx_bmcc_consumption_revision (consumption_id, revision),
    INDEX idx_bmcc_added_line (added_line_id, revision),
    CONSTRAINT chk_bmcc_kind CHECK (kind IN ('MODIFY', 'CANCEL', 'ADD', 'REVERSAL')),
    CONSTRAINT chk_bmcc_line_kind CHECK (
        (line_kind = 'ORIGINAL' AND consumption_id IS NOT NULL AND added_line_id IS NULL)
        OR (line_kind = 'ADDED' AND added_line_id IS NOT NULL AND consumption_id IS NULL)),
    CONSTRAINT fk_bmcc_close_record FOREIGN KEY (close_record_id)
        REFERENCES budget_monthly_close_records (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bmcc_transaction FOREIGN KEY (budget_transaction_id)
        REFERENCES budget_transactions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='シフト予算 月次締め後訂正履歴（追記専用 CMP-260909-2152）';

-- ─────────────────────────────────────────────
-- 原本保護: シフト予算の自動計上仕訳（月次・訂正）は SQL による削除・保護列の更新も拒否する。
-- アプリ層の 409（BudgetTransactionService.delete/reverse）と二重に守る。
-- flyway-mysql プラグインは BEGIN ... END ブロックを自動認識するため DELIMITER 命令は不要（V13.045 と同じ）。
-- ─────────────────────────────────────────────
CREATE TRIGGER trg_bt_protect_shift_budget_delete
BEFORE DELETE ON budget_transactions
FOR EACH ROW
BEGIN
    IF OLD.source_type IN ('SHIFT_BUDGET_MONTHLY', 'SHIFT_BUDGET_CORRECTION') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'CMP-260909-2152: shift budget journals (monthly/correction) cannot be deleted.';
    END IF;
END;

CREATE TRIGGER trg_bt_protect_shift_budget_update
BEFORE UPDATE ON budget_transactions
FOR EACH ROW
BEGIN
    IF OLD.source_type IN ('SHIFT_BUDGET_MONTHLY', 'SHIFT_BUDGET_CORRECTION') THEN
        IF NOT (NEW.amount <=> OLD.amount)
            OR NOT (NEW.transaction_type <=> OLD.transaction_type)
            OR NOT (NEW.transaction_date <=> OLD.transaction_date)
            OR NOT (NEW.fiscal_year_id <=> OLD.fiscal_year_id)
            OR NOT (NEW.category_id <=> OLD.category_id)
            OR NOT (NEW.scope_type <=> OLD.scope_type)
            OR NOT (NEW.scope_id <=> OLD.scope_id)
            OR NOT (NEW.source_type <=> OLD.source_type)
            OR NOT (NEW.source_id <=> OLD.source_id)
            OR NOT (NEW.deleted_at <=> OLD.deleted_at) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'CMP-260909-2152: shift budget journals (monthly/correction) are immutable.';
        END IF;
    END IF;
END;
