package codegen

data class Type(
    val type: TypeEnum,
    val name: String,
    val javadoc: String,
    val imports: MutableList<String> = mutableListOf(),
    val annotations: MutableList<String> = mutableListOf(),
    val subTypes: MutableList<Type> = mutableListOf(),
    val rawBodies: MutableList<String> = mutableListOf(),
) {
    enum class TypeEnum {
        ENUM, CLASS, INTERFACE
    }

    fun import(import: String) = this.also { it.imports.add(import) }
    fun annotation(annotation: String) = this.also { it.annotations.add(annotation) }
    fun subType(subType: Type) = this.also { it.subTypes.add(subType) }
    fun rawBody(rawBody: String) = this.also { it.rawBodies.add(rawBody) }

    fun toCode(nested: Boolean = false): String {
        val startDeclaration =
            if (nested) {
                when (type) {
                    TypeEnum.ENUM -> "enum $name {"
                    TypeEnum.CLASS -> "public static class $name {"
                    TypeEnum.INTERFACE -> "public static interface $name {"
                }
            } else {
                when (type) {
                    TypeEnum.ENUM -> "public enum $name {"
                    TypeEnum.CLASS -> "public class $name {"
                    TypeEnum.INTERFACE -> "public interface $name {"
                }
            }
        val extraAnnotations = mutableListOf<String>()
        if (type == TypeEnum.CLASS) {
            if (!annotations.any { it.contains("@Getter") }) extraAnnotations.add("@Getter")
            if (!annotations.any { it.contains("@Setter") }) extraAnnotations.add("@Setter")
            if (!annotations.contains("@Accessors(chain = true, fluent = false)")) extraAnnotations.add("@Accessors(chain = true, fluent = false)")

            if (!imports.contains("lombok.Getter")) imports.add("lombok.Getter")
            if (!imports.contains("lombok.Setter")) imports.add("lombok.Setter")
            if (!imports.contains("lombok.experimental.Accessors")) imports.add("lombok.experimental.Accessors")
        }
        val wrappedJavadoc = """
            |/**
            | * ${javadoc.split("\n").dropLastWhile {it.isEmpty()}.joinToString("\n").replace("\n", "\n * ")}
            | */
        """.trimMargin()
        return listOf(
            if (javadoc.isEmpty()) null else wrappedJavadoc,
            (annotations + extraAnnotations).joinToString("\n"),
            startDeclaration,
            subTypes.distinct().joinToString("\n\n") {
                val code = it.toCode(true)
                if (type == TypeEnum.INTERFACE) {
                    code.replaceFirst("public static ", "")
                } else {
                    code
                }
            }.prependIndent("    "),
            rawBodies.joinToString("\n").prependIndent("    "),
            "}"
        ).filterNotNull().joinToString("\n").replace("%TYPE%", name)
    }

    private fun effectiveImports(): List<String> = (imports + subTypes.flatMap { it.effectiveImports() }).distinct()

    fun importString() = effectiveImports().joinToString("\n") { "import $it;" }

    override fun hashCode(): Int {
        return type.hashCode() + name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Type) return false

        if (type != other.type) return false
        if (name != other.name) return false
        if (javadoc != other.javadoc) return false
        if (imports != other.imports) return false
        if (annotations != other.annotations) return false
        if (subTypes != other.subTypes) return false
        if (rawBodies != other.rawBodies) return false

        return true
    }
}
