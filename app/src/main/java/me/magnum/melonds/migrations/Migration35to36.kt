package me.magnum.melonds.migrations

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.getSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.magnum.melonds.domain.model.layout.BackgroundMode
import me.magnum.melonds.domain.model.layout.LayoutDisplay
import me.magnum.melonds.migrations.helper.GenericJsonArrayMigrationHelper
import java.io.File

class Migration35to36(
    private val context: Context,
    @Suppress("unused") private val layoutMigrationHelper: GenericJsonArrayMigrationHelper,
) : Migration {

    companion object {
        private const val LAYOUTS_DATA_FILE = "layouts.json"
    }

    override val from = 35
    override val to = 36

    override fun migrate() {
        val layoutsFile = File(context.filesDir, LAYOUTS_DATA_FILE)
        if (!layoutsFile.isFile) {
            return
        }

        // Assume that the default display was always used, which should be accurate for the vast majority of users
        val defaultDisplay = context.getSystemService<DisplayManager>()?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val displaySize = Point()
        defaultDisplay.getRealSize(displaySize)

        val mainLayoutDisplay = JsonObject(
            mapOf(
                "id" to JsonPrimitive(defaultDisplay.displayId),
                "type" to JsonPrimitive(LayoutDisplay.Type.BUILT_IN.name),
                "width" to JsonPrimitive(displaySize.x),
                "height" to JsonPrimitive(displaySize.y),
            )
        )
        val displaysObject = JsonObject(
            mapOf(
                "mainScreenDisplay" to mainLayoutDisplay,
                "secondaryScreenDisplay" to JsonNull,
            )
        )
        val emptySecondaryScreenLayout = JsonObject(
            mapOf(
                "backgroundId" to JsonNull,
                "backgroundMode" to JsonPrimitive(BackgroundMode.FIT_CENTER.name),
                "components" to JsonNull,
            )
        )

        val json = Json {
            explicitNulls = false
        }
        try {
            val updatedLayouts = json.parseToJsonElement(layoutsFile.readText()).jsonArray.mapNotNull { layoutElement ->
                val layout = layoutElement.jsonObject

                // Filter out external layouts
                if (layout["type"]?.jsonPrimitive?.content == "EXTERNAL") {
                    return@mapNotNull null
                }

                try {
                    val newVariants = layout["layoutVariants"]!!.jsonArray.map { entryElement ->
                        val entryObj = entryElement.jsonObject
                        val oldVariant = entryObj["variant"]!!.jsonObject
                        val oldLayout = entryObj["layout"]!!.jsonObject

                        // Build new variant: keep uiSize, orientation, folds from old; add insets and displays
                        val newVariant = JsonObject(
                            buildMap {
                                put("uiSize", oldVariant["uiSize"]!!)
                                put("orientation", oldVariant["orientation"]!!)
                                put("folds", oldVariant["folds"]!!)
                                put("displays", displaysObject)
                            }
                        )

                        // Build new layout: wrap old layout fields into mainScreenLayoutDto, add empty secondary
                        val mainScreenLayout = JsonObject(
                            buildMap {
                                put("backgroundId", oldLayout["backgroundId"] ?: JsonNull)
                                put("backgroundMode", oldLayout["backgroundMode"]!!)
                                // Remap components: keep only rect and component fields from each
                                val oldComponents = oldLayout["components"]
                                if (oldComponents != null && oldComponents !is JsonNull) {
                                    val remappedComponents = oldComponents.jsonArray.map { compElement ->
                                        val component = compElement.jsonObject
                                        val oldRect = component["rect"]!!.jsonObject
                                        val newRect = JsonObject(
                                            mapOf(
                                                "x" to oldRect["x"]!!,
                                                "y" to oldRect["y"]!!,
                                                "width" to oldRect["width"]!!,
                                                "height" to oldRect["height"]!!,
                                            )
                                        )
                                        JsonObject(
                                            mapOf(
                                                "rect" to newRect,
                                                "component" to component["component"]!!,
                                            )
                                        )
                                    }
                                    put("components", JsonArray(remappedComponents))
                                } else {
                                    put("components", JsonNull)
                                }
                            }
                        )

                        val newLayout = JsonObject(
                            mapOf(
                                "mainScreenLayoutDto" to mainScreenLayout,
                                "secondaryScreenLayoutDto" to emptySecondaryScreenLayout,
                            )
                        )

                        JsonObject(
                            mapOf(
                                "variant" to newVariant,
                                "layout" to newLayout,
                            )
                        )
                    }

                    // Build new configuration: keep all fields except target, update layoutVariants
                    val newConfiguration = JsonObject(
                        layout.toMutableMap().apply {
                            set("layoutVariants", JsonArray(newVariants))
                        }
                    )
                    newConfiguration
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            val finalArray = JsonArray(updatedLayouts)
            layoutsFile.writeText(finalArray.toString())
        } catch (_: Exception) {
            layoutsFile.delete()
        }
    }
}
