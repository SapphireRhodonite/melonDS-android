package me.magnum.melonds.migrations

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.getSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.magnum.melonds.domain.model.Point
import me.magnum.melonds.domain.model.layout.Insets
import me.magnum.melonds.domain.model.ui.Orientation
import java.io.File

class Migration39to40(private val context: Context) : Migration {

    companion object {
        private const val LAYOUTS_DATA_FILE = "layouts.json"
    }

    override val from = 39
    override val to = 40

    override fun migrate() {
        val layoutsFile = File(context.filesDir, LAYOUTS_DATA_FILE)
        if (!layoutsFile.isFile) {
            return
        }

        val defaultDisplayCutoutInsets = getDisplayCutoutInsets()
        val isCurrentlyPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val json = Json {
            explicitNulls = false
        }
        try {
            val updatedLayouts = json.parseToJsonElement(layoutsFile.readText()).jsonArray.mapNotNull {
                try {
                    val newVariants = it.jsonObject["layoutVariants"]!!.jsonArray.map {
                        val variant = it.jsonObject["variant"]!!.jsonObject
                        val (componentOffset, insets) = if (isCurrentlyPortrait) {
                            if (variant["orientation"]?.jsonPrimitive?.content == Orientation.PORTRAIT.name) {
                                val insets = mapOf(
                                    "left" to JsonPrimitive(defaultDisplayCutoutInsets.left),
                                    "top" to JsonPrimitive(defaultDisplayCutoutInsets.top),
                                    "right" to JsonPrimitive(defaultDisplayCutoutInsets.right),
                                    "bottom" to JsonPrimitive(defaultDisplayCutoutInsets.bottom),
                                )
                                val offset = Point(defaultDisplayCutoutInsets.left, defaultDisplayCutoutInsets.top)
                                offset to insets
                            } else {
                                // Create layout for when the screen is rotated 90º CCW
                                val insets = mapOf(
                                    "left" to JsonPrimitive(defaultDisplayCutoutInsets.top),
                                    "top" to JsonPrimitive(defaultDisplayCutoutInsets.right),
                                    "right" to JsonPrimitive(defaultDisplayCutoutInsets.bottom),
                                    "bottom" to JsonPrimitive(defaultDisplayCutoutInsets.left),
                                )
                                val offset = Point(defaultDisplayCutoutInsets.top, defaultDisplayCutoutInsets.right)
                                offset to insets
                            }
                        } else {
                            if (variant["orientation"]?.jsonPrimitive?.content == Orientation.PORTRAIT.name) {
                                // Create layout for when the screen is rotated 90º CW
                                val insets = mapOf(
                                    "left" to JsonPrimitive(defaultDisplayCutoutInsets.bottom),
                                    "top" to JsonPrimitive(defaultDisplayCutoutInsets.left),
                                    "right" to JsonPrimitive(defaultDisplayCutoutInsets.top),
                                    "bottom" to JsonPrimitive(defaultDisplayCutoutInsets.right),
                                )
                                val offset = Point(defaultDisplayCutoutInsets.bottom, defaultDisplayCutoutInsets.left)
                                offset to insets
                            } else {
                                val insets = mapOf(
                                    "left" to JsonPrimitive(defaultDisplayCutoutInsets.left),
                                    "top" to JsonPrimitive(defaultDisplayCutoutInsets.top),
                                    "right" to JsonPrimitive(defaultDisplayCutoutInsets.right),
                                    "bottom" to JsonPrimitive(defaultDisplayCutoutInsets.bottom),
                                )
                                val offset = Point(defaultDisplayCutoutInsets.left, defaultDisplayCutoutInsets.top)
                                offset to insets
                            }
                        }
                        val mainScreenDisplay = variant["displays"]!!.jsonObject["mainScreenDisplay"]!!.jsonObject
                        val mainScreenLayout = it.jsonObject["layout"]!!.jsonObject["mainScreenLayoutDto"]!!.jsonObject
                        val mainDisplayLayout = if (mainScreenDisplay["id"]!!.jsonPrimitive.int == Display.DEFAULT_DISPLAY) {
                            val updatedComponents = mainScreenLayout["components"]?.jsonArray?.map {
                                val updatedComponent = it.jsonObject.toMutableMap().apply {
                                    val updatedRect = get("rect")!!.jsonObject.toMutableMap().apply {
                                        set("x", JsonPrimitive(get("x")!!.jsonPrimitive.int + componentOffset.x))
                                        set("y", JsonPrimitive(get("y")!!.jsonPrimitive.int + componentOffset.y))
                                    }
                                    set("rect", JsonObject(updatedRect))
                                }
                                JsonObject(updatedComponent)
                            }

                            val updatedLayout = mainScreenLayout.toMutableMap().apply {
                                set("components", updatedComponents?.let { JsonArray(it) } ?: JsonNull)
                            }
                            JsonObject(updatedLayout)
                        } else {
                            mainScreenLayout
                        }

                        val secondaryScreenDisplay = variant["displays"]?.jsonObject?.get("secondaryScreenDisplay")?.jsonObject
                        val secondaryScreenLayout = it.jsonObject["layout"]!!.jsonObject["secondaryScreenLayoutDto"]!!.jsonObject
                        val secondaryDisplayLayout = if (secondaryScreenDisplay?.get("id")?.jsonPrimitive?.int == Display.DEFAULT_DISPLAY) {
                            val updatedComponents = secondaryScreenLayout["components"]?.jsonArray?.map {
                                val updatedComponent = it.jsonObject.toMutableMap().apply {
                                    val updatedRect = get("rect")!!.jsonObject.toMutableMap().apply {
                                        set("x", JsonPrimitive(get("x")!!.jsonPrimitive.int + componentOffset.x))
                                        set("y", JsonPrimitive(get("y")!!.jsonPrimitive.int + componentOffset.y))
                                    }
                                    set("rect", JsonObject(updatedRect))
                                }
                                JsonObject(updatedComponent)
                            }

                            val updatedLayout = secondaryScreenLayout.toMutableMap().apply {
                                set("components", updatedComponents?.let { JsonArray(it) } ?: JsonNull)
                            }
                            JsonObject(updatedLayout)
                        } else {
                            secondaryScreenLayout
                        }

                        val newSize = if (secondaryScreenDisplay?.get("id")?.jsonPrimitive?.int == Display.DEFAULT_DISPLAY) {
                            JsonObject(
                                mapOf(
                                    "x" to secondaryScreenDisplay["width"]!!.jsonPrimitive,
                                    "y" to secondaryScreenDisplay["height"]!!.jsonPrimitive,
                                )
                            )
                        } else {
                            JsonObject(
                                mapOf(
                                    "x" to mainScreenDisplay["width"]!!.jsonPrimitive,
                                    "y" to mainScreenDisplay["height"]!!.jsonPrimitive,
                                )
                            )
                        }

                        val updatedLayoutEntry = it.jsonObject.toMutableMap().apply {
                            val updatedVariant = get("variant")!!.jsonObject.toMutableMap().apply {
                                set("uiSize", newSize)
                                set("insets", JsonObject(insets))
                            }
                            val updatedLayout = mapOf(
                                "mainScreenLayoutDto" to mainDisplayLayout,
                                "secondaryScreenLayoutDto" to secondaryDisplayLayout,
                            )

                            set("variant", JsonObject(updatedVariant))
                            set("layout", JsonObject(updatedLayout))
                        }
                        JsonObject(updatedLayoutEntry)
                    }

                    val updatedConfiguration = it.jsonObject.toMutableMap().apply {
                        set("layoutVariants", JsonArray(newVariants))
                    }
                    JsonObject(updatedConfiguration)
                } catch (_: Exception) {
                    null
                }
            }

            val finalArray = JsonArray(updatedLayouts)
            layoutsFile.writeText(finalArray.toString())
        } catch (_: Exception) {
            layoutsFile.delete()
        }
    }

    private fun getDisplayCutoutInsets(): Insets {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Insets.Zero
        }

        val windowManager = context.getSystemService<WindowManager>() ?: return Insets.Zero

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutoutInsets = windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.displayCutout())
            Insets(cutoutInsets.left, cutoutInsets.top, cutoutInsets.right, cutoutInsets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val displayCutout = windowManager.defaultDisplay.cutout ?: return Insets.Zero
            Insets(displayCutout.safeInsetLeft, displayCutout.safeInsetTop, displayCutout.safeInsetRight, displayCutout.safeInsetBottom)
        }
    }
}