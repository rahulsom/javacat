package codegen

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File

class Main {
    fun process(schema: File, mainDir: File, packageName: String, testDir: File) {
        val parseOptions = ParseOptions()

        val result = OpenAPIParser().readContents(schema.readText(), listOf(), parseOptions)
        val openAPI = result.openAPI
        Holder.instance.get().openAPI = openAPI
        PathsBuilder.buildApis(openAPI, mainDir, packageName, "${packageName}.rest", "${packageName}.rest.api", testDir)
        WebhooksBuilder.buildWebhooks(openAPI, mainDir, packageName, "${packageName}.rest", "${packageName}.rest.webhooks", testDir)

        Holder.instance.get().withSchemaStack("#", "components", "schemas") {
            SchemasBuilder.buildSchemas(openAPI, mainDir, packageName, "${packageName}.rest", "${packageName}.rest.schemas")
        }
    }

}
