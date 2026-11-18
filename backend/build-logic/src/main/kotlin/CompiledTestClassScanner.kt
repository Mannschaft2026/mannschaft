import java.io.DataInputStream
import java.io.File

// =============================================================================
// CompiledTestClassScanner: コンパイル済みテストクラス（.class）から「実行対象になり得るクラス」の情報を作る
// （CMP-261111-0508）。ASM 等の外部依存を build-logic に足さず、必要最小限の class ファイル読み取りだけを行う。
//
// 判定の方針（ShardAssignment.executableTopLevelClasses が使う CompiledTestClassFile を作る）:
//  - isAbstract: ACC_ABSTRACT（抽象クラス・interface）。
//  - hasJupiterTests: 自身または（コンパイル対象内の）親クラス・interface が JUnit のテストメソッド
//    （@Test / @ParameterizedTest / @RepeatedTest / @TestFactory / @TestTemplate、およびそれらをメタアノテーションに
//    持つ「コンパイル対象内で宣言された」独自注釈）を持つ。JUnit と同じく、子が同じ名前・記述子のメソッドを
//    注釈なしで override していれば親のそのメソッドは数えない。private / static は数えない。
//  - hasTestMethods: hasJupiterTests、または ArchUnit の @AnalyzeClasses を持つ
//    （@AnalyzeClasses は archUnitTest タスクだけが実行する。通常 :test の集合には ShardAssignment 側で入れない）。
//  - 注意: 本走査が取りこぼしても安全側に倒してある。集合に入らないクラスも ShardAssignment が FQCN ハッシュで
//    ちょうど 1 つの shard へ入れるため、全 shard で重複実行されることはない（重み表上は未掲載扱いになるだけ）。
//    コンパイル対象外（ライブラリ）で宣言された独自メタ注釈は読めない。
//  - jupiter の @Tag("perf" / "archunit" / "er-diagram") を持つクラス（通常 test では実行されず、専用タスクが
//    実行する）は、通常の shard 割り当て対象から外す。@AnalyzeClasses を持つクラスは ArchUnit エンジンで
//    実行されるため、この除外の対象にしない。
// =============================================================================
object CompiledTestClassScanner {
    /** 通常の test タスクが excludeTags している JUnit タグ。 */
    val EXCLUDED_TAGS: Set<String> = setOf("perf", "archunit", "er-diagram")

    private val TEST_ANNOTATIONS = setOf(
        "Lorg/junit/jupiter/api/Test;",
        "Lorg/junit/jupiter/params/ParameterizedTest;",
        "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/api/TestFactory;",
        "Lorg/junit/jupiter/api/TestTemplate;",
        "Lorg/junit/Test;",
    )
    private const val ANALYZE_CLASSES = "Lcom/tngtech/archunit/junit/AnalyzeClasses;"
    private const val JUPITER_TAG = "Lorg/junit/jupiter/api/Tag;"
    private const val JUPITER_TAGS = "Lorg/junit/jupiter/api/Tags;"

    /** class ファイル1件から読み取った生の情報。 */
    class RawClassInfo(
        val internalName: String,
        val superName: String?,
        val interfaces: List<String>,
        val isAbstract: Boolean,
        val ownTests: Boolean,
        val analyzeClasses: Boolean,
        val tags: Set<String>,
        /** メソッドの名前・記述子・注釈・アクセスフラグ。空なら ownTests だけを手掛かりにする（単体テスト用の簡易構築）。 */
        val methods: List<MethodInfo> = emptyList(),
        /** クラス自身に付いた注釈の型（独自注釈がメタアノテーションに @Test を持つかの判定に使う）。 */
        val classAnnotationTypes: Set<String> = emptySet(),
        val isAnnotation: Boolean = false,
    )

    /** メソッド1件。annotationTypes は "Lpkg/Name;" 形式。 */
    class MethodInfo(val name: String, val desc: String, val annotationTypes: Set<String>, val access: Int)

    /** classesDirs 配下の全 .class を読み、継承込みの判定をして CompiledTestClassFile の一覧を返す。 */
    fun scan(classesDirs: Collection<File>): List<CompiledTestClassFile> {
        val infos = LinkedHashMap<String, Pair<String, RawClassInfo>>()
        classesDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                val info = f.inputStream().buffered().use { parse(it) }
                infos[info.internalName] = rel to info
            }
        }
        return resolve(infos.values.toList())
    }

    /** 継承（コンパイル対象内の親クラス・interface）を辿って hasTestMethods / 除外タグを確定する。 */
    fun resolve(entries: List<Pair<String, RawClassInfo>>): List<CompiledTestClassFile> {
        val byName = entries.associate { it.second.internalName to it.second }
        fun ancestors(info: RawClassInfo, seen: MutableSet<String> = mutableSetOf()): List<RawClassInfo> {
            val parents = (listOfNotNull(info.superName) + info.interfaces).mapNotNull { byName[it] }
            return parents.filter { seen.add(it.internalName) }.flatMap { listOf(it) + ancestors(it, seen) }
        }
        // テストを示す注釈の集合。コンパイル対象内の独自注釈（@interface）が、これらを注釈に持てば仲間入りする
        // （例: @Test をメタアノテーションに持つ @FastTest）。推移的に不動点まで広げる。
        val markers = TEST_ANNOTATIONS.toMutableSet()
        do {
            val added = entries.map { it.second }
                .filter { it.isAnnotation && it.classAnnotationTypes.any { t -> t in markers } }
                .any { markers.add("L${it.internalName};") }
        } while (added)
        // 子から親の順に走り、最初に現れた（最も子の）宣言だけを有効にする。子が注釈なしで override したら親の注釈は無効。
        fun hasJupiterTests(chain: List<RawClassInfo>): Boolean {
            val seen = mutableSetOf<String>()
            for (c in chain) {
                if (c.methods.isEmpty() && c.ownTests) return true
                for (m in c.methods) {
                    // private / static は JUnit が検出せず、親のメソッドも override しない。bridge / synthetic も対象外。
                    if (m.access and (0x0002 or 0x0008 or 0x0040 or 0x1000) != 0) continue
                    if (!seen.add(m.name + m.desc)) continue
                    if (m.annotationTypes.any { it in markers }) return true
                }
            }
            return false
        }
        val resolved = entries.map { (path, info) ->
            val chain = listOf(info) + ancestors(info)
            val jupiter = hasJupiterTests(chain)
            val hasTests = jupiter || chain.any { it.analyzeClasses }
            val excludedByTag = !info.analyzeClasses && chain.any { c -> c.tags.any { it in EXCLUDED_TAGS } }
            Triple(path, info, Triple(hasTests, jupiter, excludedByTag))
        }
        // タグで除外するのはトップレベルクラス単位（ネストクラスはトップレベルに従う）。
        val excludedTop = resolved.filter { it.third.third && !it.second.internalName.contains('$') }
            .map { it.second.internalName }.toSet()
        return resolved
            .filter { (_, info, _) -> info.internalName.substringBefore('$') !in excludedTop }
            .map { (path, info, flags) -> CompiledTestClassFile(path, info.isAbstract, flags.first, flags.second) }
    }

    /** class ファイルの構造を最小限だけ読む（定数プール・アクセスフラグ・親・メソッド注釈・クラス注釈）。 */
    fun parse(stream: java.io.InputStream): RawClassInfo {
        val input = DataInputStream(stream)
        require(input.readInt() == 0xCAFEBABE.toInt()) { "class ファイルではない" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val cpCount = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(cpCount)
        val classNameIndex = IntArray(cpCount)
        var i = 1
        while (i < cpCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); i++ }
                7 -> classNameIndex[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                else -> error("未知の定数プールタグ: $tag")
            }
            i++
        }
        fun className(index: Int): String? = if (index == 0) null else utf8[classNameIndex[index]]
        val access = input.readUnsignedShort()
        val thisName = className(input.readUnsignedShort())!!
        val superName = className(input.readUnsignedShort())
        val interfaces = (0 until input.readUnsignedShort()).map { className(input.readUnsignedShort())!! }

        val reader = AnnotationReader(input, utf8)
        fun readAnnotation(strings: MutableSet<String>): String = reader.readAnnotation(strings)
        // 属性列を読む。RuntimeVisibleAnnotations だけ解釈し、(注釈型, 文字列定数) の一覧を返す。
        fun readAttributes(): List<Pair<String, Set<String>>> {
            val found = mutableListOf<Pair<String, Set<String>>>()
            repeat(input.readUnsignedShort()) {
                val name = utf8[input.readUnsignedShort()]
                val length = input.readInt()
                if (name == "RuntimeVisibleAnnotations") {
                    repeat(input.readUnsignedShort()) {
                        val strings = mutableSetOf<String>()
                        val type = readAnnotation(strings)
                        found += type to strings
                    }
                } else {
                    input.skipBytes(length)
                }
            }
            return found
        }
        repeat(input.readUnsignedShort()) { // fields
            input.skipBytes(6)
            readAttributes()
        }
        var ownTests = false
        val methods = mutableListOf<MethodInfo>()
        repeat(input.readUnsignedShort()) { // methods
            val methodAccess = input.readUnsignedShort()
            val methodName = utf8[input.readUnsignedShort()] ?: ""
            val methodDesc = utf8[input.readUnsignedShort()] ?: ""
            val annotationTypes = readAttributes().map { it.first }.toSet()
            if (annotationTypes.any { it in TEST_ANNOTATIONS }) ownTests = true
            methods += MethodInfo(methodName, methodDesc, annotationTypes, methodAccess)
        }
        val classAnnotations = readAttributes()
        val tags = classAnnotations
            .filter { it.first == JUPITER_TAG || it.first == JUPITER_TAGS }
            .flatMap { it.second }.toSet()
        return RawClassInfo(
            internalName = thisName,
            superName = superName,
            interfaces = interfaces,
            isAbstract = (access and 0x0400) != 0,
            ownTests = ownTests,
            analyzeClasses = classAnnotations.any { it.first == ANALYZE_CLASSES },
            tags = tags,
            methods = methods,
            classAnnotationTypes = classAnnotations.map { it.first }.toSet(),
            isAnnotation = (access and 0x2000) != 0,
        )
    }

    /** 注釈（RuntimeVisibleAnnotations の要素）の再帰読み取り。相互再帰のためローカル関数ではなくクラスにする。 */
    private class AnnotationReader(private val input: DataInputStream, private val utf8: Array<String?>) {
        fun readElementValue(strings: MutableSet<String>) {
            when (val tag = input.readUnsignedByte().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c' -> input.skipBytes(2)
                's' -> utf8[input.readUnsignedShort()]?.let { strings += it }
                'e' -> input.skipBytes(4)
                '@' -> readAnnotation(strings)
                '[' -> repeat(input.readUnsignedShort()) { readElementValue(strings) }
                else -> error("未知の element_value タグ: $tag")
            }
        }

        fun readAnnotation(strings: MutableSet<String>): String {
            val type = utf8[input.readUnsignedShort()] ?: ""
            repeat(input.readUnsignedShort()) {
                input.skipBytes(2)
                readElementValue(strings)
            }
            return type
        }
    }
}
