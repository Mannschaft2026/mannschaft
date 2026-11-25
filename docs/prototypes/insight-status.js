// 気づき箱の状態の正本（CMP-261124-2306）。ボード（beta-inventory-board.html）が読み込み、IndexedDB の状態より優先して表示する。
//
// 書式: window.BETA_INSIGHT_STATUS = { schemaVersion: 1, items: { "<キー>": { status, pr, updatedAt, note } } }
//   キー       … ボード上の気づき id の完全一致、または住民 overlay の元 ID（例: ALIC-CMP0035-B-YA-01）。手入力 NOTE は完全一致のみ。
//   status     … inbox（未整理）/ planned（課題化済）/ fixing（修正中）/ done（対応済）
//   pr         … 対応 PR（例: "#4240"）。無ければ空文字
//   updatedAt  … ISO 8601 の日付または日時（例: "2026-11-24"）。ボード側がこれより新しい変更を持てば「未書き出し」として区別する
//   note       … 補足（任意）
//
// 更新手順: この書式を PR で直接編集する。または、ボードで状態を動かしたあと「状態を書き出す」で生成した insight-status.js で置き換える。
// 不正な項目は適用されず、ボードの警告表示と validate-beta-inventory-board.mjs（--insight-status）で検出される。
window.BETA_INSIGHT_STATUS = {
  "schemaVersion": 1,
  "items": {
    "ALIC-CMP0035-B-YA-01": {
      "status": "done",
      "pr": "#4240",
      "updatedAt": "2026-11-24",
      "note": "main 7fa6d08a2 で修正済み（組織告知一覧の宛先判定）"
    },
    "ALIC-CMP1152-R1-04": {
      "status": "done",
      "pr": "#4854",
      "updatedAt": "2026-11-25",
      "note": "下書き詳細に保存済み設問を表示し、公開時の設問重複と「公開して作成」の未公開も解消"
    },
    "ALIC-CMP1152-R3-02": {
      "status": "fixing",
      "pr": "#4855",
      "updatedAt": "2026-11-25",
      "note": "閉じるボタンは 44x44px にして解消。トーストの二重表示は未対応"
    }
  }
};
