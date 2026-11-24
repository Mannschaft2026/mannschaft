"""CI 収集側（run-ci-tests-with-observability.sh）の MFA 行抽出正規表現と、出力側 IT の形式の整合を検証する（CMP-261123-1434）。

収集側は Groovy の完全一致（==~）で行を拾うため、出力側の形式が 1 項目でもずれると
mfa-intervals.log / mfa-resources.log へ黙って何も収集されなくなる。両者を実ファイルから読み、
新形式の行が一致し、旧形式（limitMillis 付き）の行が一致しないことを確かめる。
"""
import re
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
COLLECTOR = SCRIPTS / "run-ci-tests-with-observability.sh"
EMITTER = SCRIPTS.parent / "src/test/java/com/mannschaft/app/auth/mfa/MfaBackupCodeLatencyIT.java"


def collector_pattern(tag):
    """収集側の Groovy スラッシュ正規表現 /\[tag\] .../ を 1 つだけ取り出す。"""
    script = COLLECTOR.read_text(encoding="utf-8")
    found = re.findall(r"it ==~ /(\\\[" + re.escape(tag) + r"\\\][^/]*)/", script)
    if len(found) != 1:
        raise AssertionError(f"{tag} の収集正規表現が 1 つではない: {found}")
    return re.compile(found[0])


def emitted_lines(tag):
    """出力側の "[tag] ...".formatted 書式を実値で埋めた行を返す。"""
    source = EMITTER.read_text(encoding="utf-8")
    formats = re.findall(r'"(\[' + re.escape(tag) + r'\] [^"]*)"\.formatted', source)
    if not formats:
        raise AssertionError(f"{tag} の出力書式が見つからない")
    lines = []
    for fmt in formats:
        for path_kind in ("all-mismatch", "last-match"):
            line = fmt.replace("pathKind=%s", "pathKind=" + path_kind, 1)
            line = line.replace("%s", path_kind).replace("%d", "1234")
            lines.append(line)
    return lines


class MfaObservabilityPatternTest(unittest.TestCase):
    def test_latency_new_format_is_collected(self):
        pattern = collector_pattern("mfa-latency")
        sample = "[mfa-latency] pathKind=all-mismatch startEpochMillis=1790000000000 endEpochMillis=1790000003021 elapsedMillis=3021 pid=4242"
        self.assertIsNotNone(pattern.fullmatch(sample))
        self.assertIsNotNone(pattern.fullmatch(sample.replace("all-mismatch", "last-match")))

    def test_latency_old_format_with_limit_is_not_collected(self):
        # 判定は照合回数へ移り、壁時計の上限値は出力しない。旧形式が混ざれば出力側の退行として気付けるよう拒否する。
        pattern = collector_pattern("mfa-latency")
        old = "[mfa-latency] pathKind=last-match startEpochMillis=1790000000000 endEpochMillis=1790000003491 elapsedMillis=3491 limitMillis=3000 pid=4242"
        self.assertIsNone(pattern.fullmatch(old))

    def test_latency_rejects_unknown_path_kind_and_trailing_text(self):
        pattern = collector_pattern("mfa-latency")
        base = "[mfa-latency] pathKind=all-mismatch startEpochMillis=1 endEpochMillis=2 elapsedMillis=1 pid=3"
        self.assertIsNone(pattern.fullmatch(base.replace("all-mismatch", "other")))
        self.assertIsNone(pattern.fullmatch(base + " extra=1"))

    def test_emitter_latency_lines_match_collector(self):
        pattern = collector_pattern("mfa-latency")
        lines = emitted_lines("mfa-latency")
        self.assertEqual(len(lines), 4, "全不一致・最後一致の 2 書式 × pathKind 2 種")
        for line in lines:
            self.assertIsNotNone(pattern.fullmatch(line), line)

    def test_emitter_resources_lines_match_collector(self):
        pattern = collector_pattern("mfa-resources")
        for line in emitted_lines("mfa-resources"):
            self.assertIsNotNone(pattern.fullmatch(line), line)
        self.assertIsNotNone(pattern.fullmatch(
            "[mfa-resources] pathKind=last-match startEpochMillis=1 endEpochMillis=2 pid=3 threadCpuNanos=-1 gcCount=-1 gcTimeMillis=-1"))


if __name__ == "__main__":
    unittest.main()
