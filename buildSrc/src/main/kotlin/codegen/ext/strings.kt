package codegen.ext

fun String.pascalCase() = this
    .replace(".", "-")
    .replace("_", "-")
    .split('-')
    .joinToString("") { it.replaceFirstChar { x -> x.uppercaseChar() } }

fun String.camelCase() = this.pascalCase().replaceFirstChar { it.lowercaseChar() }

fun String.trainCase() = this
    .replace(".", "-")
    .replace("_", "-")
    .replace("'", "-")
    .replace(" ", "-")
    .replace(":", "-")
    .replace("/", "-")
    .replace(Regex("-+"), "-")
    .split('-')
    .map { it.uppercase() }
    .joinToString("_")
    .let { if (it[0].isDigit()) "_$it" else it }

fun String.unkeywordize() =
    mapOf(
        "package" to "the-package",
        "protected" to "is-protected",
        "private" to "is-private",
        "public" to "is-public",
        "@timestamp" to "timestamp",
        "default" to "is-default",
        "+1" to "plus-one",
        "-1" to "minus-one",
        "*" to "asterisk",
        "reactions-+1" to "reactions-plus-one",
        "reactions--1" to "reactions-minus-one",
        "/" to "slash",
        "/docs" to "slash-docs",
    )[this] ?: this
