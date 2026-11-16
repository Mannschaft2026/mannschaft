package com.mannschaft.app.budget;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予算・会計機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum BudgetErrorCode implements ErrorCode {

    /** 年度が見つからない */
    BUDGET_001("BUDGET_001", "指定された予算年度が見つかりません", Severity.WARN),

    /** 費目が見つからない */
    BUDGET_002("BUDGET_002", "指定された予算費目が見つかりません", Severity.WARN),

    /** トランザクションが見つからない */
    BUDGET_003("BUDGET_003", "指定された予算取引が見つかりません", Severity.WARN),

    /** 年度がCLOSED状態 */
    BUDGET_004("BUDGET_004", "年度が締め切られているため操作できません", Severity.WARN),

    /** 費目種別不一致 */
    BUDGET_005("BUDGET_005", "費目の種別が取引種別と一致しません", Severity.WARN),

    /** 予算上限超過 */
    BUDGET_006("BUDGET_006", "予算配分額の上限を超過しています", Severity.WARN),

    /** 費目数上限超過 */
    BUDGET_007("BUDGET_007", "費目数が上限を超えています", Severity.WARN),

    /** 承認待ち状態 */
    BUDGET_008("BUDGET_008", "取引は承認待ち状態のため操作できません", Severity.WARN),

    /** 取消済み */
    BUDGET_009("BUDGET_009", "取引は既に取消済みです", Severity.WARN),

    /** 取消仕訳の取消不可 */
    BUDGET_010("BUDGET_010", "取消仕訳を再度取り消すことはできません", Severity.WARN),

    /** 報告書が見つからない */
    BUDGET_011("BUDGET_011", "指定された予算報告書が見つかりません", Severity.WARN),

    /** 設定が見つからない */
    BUDGET_012("BUDGET_012", "指定された予算設定が見つかりません", Severity.WARN),

    /** CSVエラー */
    BUDGET_013("BUDGET_013", "CSVファイルの処理中にエラーが発生しました", Severity.ERROR),

    /** 添付数上限超過 */
    BUDGET_014("BUDGET_014", "添付ファイル数が上限を超えています", Severity.WARN),

    /** ファイルサイズ上限超過 */
    BUDGET_015("BUDGET_015", "ファイルサイズが上限を超えています", Severity.WARN),

    /** 年度期間重複 */
    BUDGET_016("BUDGET_016", "指定された期間は既存の年度と重複しています", Severity.WARN),

    /** 年度名重複 */
    BUDGET_017("BUDGET_017", "同じ名前の年度が既に存在します", Severity.WARN),

    /** 費目名重複 */
    BUDGET_018("BUDGET_018", "同じ名前の費目が既に存在します", Severity.WARN),

    /** 階層数上限超過 */
    BUDGET_019("BUDGET_019", "費目の階層数が上限を超えています", Severity.WARN),

    /** トランザクション存在のため年度削除不可 */
    BUDGET_020("BUDGET_020", "取引が存在するため年度を削除できません", Severity.WARN),

    /** 添付ファイルが見つからない */
    BUDGET_021("BUDGET_021", "指定された添付ファイルが見つかりません", Severity.WARN),

    /** 生存する子費目があるため削除不可 */
    BUDGET_022("BUDGET_022", "子費目が存在するため削除できません", Severity.WARN),

    // ── CMP-260909-2152 シフト予算 月次締め後の訂正 ──

    /** シフト予算の自動計上仕訳（月次・訂正）は汎用の削除・取消ができない（訂正 API から行う） */
    BUDGET_023("BUDGET_023", "シフト予算の自動計上仕訳は削除・取消できません。月次締め後の訂正から行ってください", Severity.WARN),

    /** 対象月の月次締め仕訳が無い（未締め・別月・別割当） */
    BUDGET_024("BUDGET_024", "対象月の月次締め仕訳が見つかりません", Severity.WARN),

    /** 対象月の月次締め仕訳が複数ある（整合性異常） */
    BUDGET_025("BUDGET_025", "対象月の月次締め仕訳が複数あるため訂正できません", Severity.WARN),

    /** expected_revision が現在の訂正番号と異なる */
    BUDGET_026("BUDGET_026", "他の訂正と競合したため処理を中断しました。再読込のうえ再試行してください", Severity.WARN),

    /** 訂正対象の明細が無い */
    BUDGET_027("BUDGET_027", "訂正対象の明細が見つかりません", Severity.WARN),

    /** 取消対象の訂正が無い */
    BUDGET_028("BUDGET_028", "取消対象の訂正が見つかりません", Severity.WARN),

    /** 明細の最新の訂正ではないため取り消せない */
    BUDGET_029("BUDGET_029", "最新の訂正のみ取り消せます", Severity.WARN),

    /** 同じ勤務枠・同じ人の明細が既に有効なため追加・復活できない */
    BUDGET_030("BUDGET_030", "同じ勤務枠・同じ人の明細が既に計上されています", Severity.WARN),

    /** 同じ勤務枠・同じ人の予定（PLANNED）の消化が残っている */
    BUDGET_031("BUDGET_031", "同じ勤務枠・同じ人の予定の消化が残っているため追加できません", Severity.WARN),

    /** 同じ command_id で内容の違う要求が来た */
    BUDGET_032("BUDGET_032", "同じ操作IDで異なる内容の訂正が送られました", Severity.WARN),

    /** 締め記録と確定明細の合計・件数が原仕訳と一致しない */
    BUDGET_033("BUDGET_033", "確定明細の合計が月次締め仕訳と一致しないため訂正できません", Severity.WARN),

    /** 状態遷移として成り立たない訂正（変更なし・取消済みの再取消など） */
    BUDGET_034("BUDGET_034", "この明細にはその訂正を行えません（変更がない、または既に取消済みです）", Severity.WARN),

    /** 訂正要求の入力が不正 */
    BUDGET_035("BUDGET_035", "訂正の入力が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
