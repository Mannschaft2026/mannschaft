import java.io.DataInputStream
import java.io.File

// =============================================================================
// CompiledTestClassScanner: コンパイル済みテストクラス（.class）から「実行対象になり得るクラス」の情報を作る
// （CMP-261111-0508）。ASM 等の外部依存を build-logic に足さず、必要最小限の class ファイル読み取りだけを行う。
//
// 判定の方針（ShardAssignment.executableTopLevelClasses が使う CompiledTestClassFile を作る）:
//  - isAbstract: ACC_ABSTRACT（抽象クラス・interface）。
//  - hasTestMethods: 自身または（コンパイル対象内の）親クラス・interface が JUnit のテストメソッド
//    （@Test / @ParameterizedTest / @RepeatedTest / @TestFactory / @TestTemplate）を持つ、または
//    ArchUnit の @AnalyzeClasses を持つ（archUnitTest タスクで実行される。従来どおり shard に分割する）。
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
    )

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
        val resolved = entries.map { (path, info) ->
            val chain = listOf(info) + ancestors(info)
            val hasTests = chain.any { it.ownTests || it.analyzeClasses }
            val excludedByTag = !info.analyzeClasses && chain.any { c -> c.tags.any { it in EXCLUDED_TAGS } }
            Triple(path, info, hasTests to excludedByTag)
        }
        // タグで除外するのはトップレベルクラス単位（ネストクラスはトップレベルに従う）。
        val excludedTop = resolved.filter { it.third.second && !it.second.internalName.contains('$') }
            .map { it.second.internalName }.toSet()
        return resolved
            .filter { (_, info, _) -> info.internalName.substringBefore('$') !in excludedTop }
            .map { (path, info, flags) -> CompiledTestClassFile(path, info.isAbstract, flags.first) }
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
        repeat(input.readUnsignedShort()) { // methods
            input.skipBytes(6)
            if (readAttributes().any { it.first in TEST_ANNOTATIONS }) ownTests = true
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
