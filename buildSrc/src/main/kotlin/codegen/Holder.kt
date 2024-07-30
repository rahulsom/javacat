package codegen

import io.swagger.v3.oas.models.OpenAPI

class Holder {
    var openAPI: OpenAPI? = null

    val schemaStack = mutableListOf<String>()

    fun getSchemaStackRef() = schemaStack.joinToString("/") { it.replace("/", "~1") }

    fun isNestedWithSameName(key: String) = schemaStack.dropLast(1).contains(key)

    fun <T> withSchemaStack(vararg element: String, block: () -> T): T {
        val backupStack = schemaStack.toList()
        schemaStack.addAll(element)
        val retval = block()
        schemaStack.clear()
        schemaStack.addAll(backupStack)
        return retval
    }

    fun <T> withNewSchemaStack(vararg element: String, block: () -> T): T {
        val backupStack = schemaStack.toList()
        schemaStack.clear()
        schemaStack.addAll(element)
        val retval = block()
        schemaStack.clear()
        schemaStack.addAll(backupStack)
        return retval
    }

    companion object {
        val instance = ThreadLocal.withInitial { Holder() }!!
    }
}
