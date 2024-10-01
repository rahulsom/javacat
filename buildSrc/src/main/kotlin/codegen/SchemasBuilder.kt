package codegen

import codegen.ext.className
import codegen.ext.fieldDefinition
import codegen.ext.referenceAndDefinition
import io.swagger.v3.oas.models.OpenAPI
import java.io.File

object SchemasBuilder {
    fun buildSchemas(openAPI: OpenAPI, outputDir: File, rootPackage: String, restPackage: String, packageName: String) {
        openAPI.components.schemas.forEach { entry ->
            val (reference, definition) = entry.referenceAndDefinition()!!
            if (definition != null) {
                val code = definition.toCode()
                val importString = definition.importString()
                CodegenHelper.createFile(packageName, entry.className(), outputDir, importString + "\n\n" + code, rootPackage)
            } else if (reference in listOf("String", "URI", "Long", "Boolean", "List<Long>", Types.LOCAL_DATE, Types.OFFSET_DATE_TIME)) {
                // ignore
            } else {
                println(listOf(entry.className(), listOf(reference, definition), entry.fieldDefinition())) // TODO
            }
        }
    }

}
