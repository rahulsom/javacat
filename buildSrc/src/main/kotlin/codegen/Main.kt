package codegen

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File

class Main {
    fun process(schema: File, outputDir: File, packageName: String) {
        val parseOptions = ParseOptions()

        val result = OpenAPIParser().readContents(schema.readText(), listOf(), parseOptions)
        val openAPI = result.openAPI
        Holder.instance.get().openAPI = openAPI
        PathsBuilder.buildApis(openAPI, outputDir,
            packageName,
            "${packageName}.rest",
            "${packageName}.rest.api"
        )
        WebhooksBuilder.buildWebhooks(openAPI, outputDir,
            "${packageName}",
            "${packageName}.rest",
            "${packageName}.rest.webhooks"
        )

        Holder.instance.get().withSchemaStack("#", "components", "schemas") {
            SchemasBuilder.buildSchemas(openAPI, outputDir,
                "${packageName}",
                "${packageName}.rest",
                "${packageName}.rest.schemas"
            )
        }
    }

}
