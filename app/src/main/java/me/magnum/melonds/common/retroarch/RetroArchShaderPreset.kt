package me.magnum.melonds.common.retroarch

object RetroArchShaderPreset {
    private val ShaderKeyRegex = Regex("""shader\d+""", RegexOption.IGNORE_CASE)
    private val IncludeRegex = Regex("""^\s*#\s*include\s+"([^"]+)"""", setOf(RegexOption.MULTILINE))
    private val SourceOutputRatioRegex = Regex(
        """sourcesize\.(?:xy|x|y)\s*[^;\n]*outputsize\.zw|outputsize\.zw\s*[^;\n]*sourcesize\.(?:xy|x|y)""",
        RegexOption.IGNORE_CASE,
    )
    private val OriginalOutputRatioRegex = Regex(
        """originalsize\.(?:xy|x|y)\s*[^;\n]*outputsize\.zw|outputsize\.zw\s*[^;\n]*originalsize\.(?:xy|x|y)""",
        RegexOption.IGNORE_CASE,
    )
    private val OutputInputRatioRegex = Regex(
        """outputsize\.xy\s*[^;\n]*(?:sourcesize|originalsize)\.zw|(?:sourcesize|originalsize)\.zw\s*[^;\n]*outputsize\.xy""",
        RegexOption.IGNORE_CASE,
    )
    private val SourceGridRegex = Regex(
        """(?:fract|floor)\s*\([^;\n]*sourcesize\.xy|sourcesize\.xy\s*[^;\n]*(?:fract|floor)""",
        RegexOption.IGNORE_CASE,
    )
    private val OriginalGridRegex = Regex(
        """(?:fract|floor|sin|modf)\s*\([^;\n]*originalsize\.xy|originalsize\.xy\s*[^;\n]*(?:fract|floor|sin|modf)""",
        RegexOption.IGNORE_CASE,
    )

    fun parseAssignments(text: String): LinkedHashMap<String, String> {
        val assignments = linkedMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '#') {
                index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                continue
            }
            if (!char.isLetterOrDigit() && char != '_') {
                index++
                continue
            }

            val keyStart = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) {
                index++
            }
            val key = text.substring(keyStart, index)
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
            if (index >= text.length || text[index] != '=') {
                continue
            }
            index++
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }

            val value = if (index < text.length && text[index] == '"') {
                index++
                val valueStart = index
                while (index < text.length && text[index] != '"') {
                    index++
                }
                text.substring(valueStart, index).also {
                    if (index < text.length && text[index] == '"') {
                        index++
                    }
                }
            } else {
                val valueStart = index
                while (index < text.length && !text[index].isWhitespace() && text[index] != '#') {
                    index++
                }
                text.substring(valueStart, index)
            }.trim()

            if (key.isNotBlank()) {
                assignments[key] = value
            }
        }
        return assignments
    }

    fun shaderReferences(assignments: Map<String, String>): List<String> {
        return assignments.entries
            .filter { ShaderKeyRegex.matches(it.key) }
            .sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            .map { it.value }
            .filter { it.isNotBlank() }
    }

    fun textureReferences(assignments: Map<String, String>): List<String> {
        val textureKeys = assignments["textures"]
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: return emptyList()
        return textureKeys.mapNotNull { assignments[it] }.filter { it.isNotBlank() }
    }

    fun passCount(assignments: Map<String, String>): Int {
        return assignments["shaders"]?.toIntOrNull()?.coerceAtLeast(0)
            ?: shaderReferences(assignments).size
    }

    fun includeReferences(shaderText: String): List<String> {
        return IncludeRegex.findAll(shaderText)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun resolveRelativePath(baseRelativePath: String, rawReference: String): String? {
        val reference = rawReference
            .replace('\\', '/')
            .trim()
            .trim('"')
        if (reference.isBlank() || reference.startsWith('/') || reference.contains("://")) {
            return null
        }

        val resolvedSegments = ArrayDeque<String>()
        baseRelativePath
            .substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { resolvedSegments.addLast(it) }

        reference.split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> {
                    if (resolvedSegments.isEmpty()) {
                        return null
                    }
                    resolvedSegments.removeLast()
                }
                else -> resolvedSegments.addLast(segment)
            }
        }

        return resolvedSegments.joinToString("/")
    }

    fun requiresNativeDsSource(presetRelativePath: String, readText: (String) -> String?): Boolean {
        val presetText = readText(presetRelativePath) ?: return false
        val assignments = parseAssignments(presetText)
        val shaderQueue = ArrayDeque<String>()
        shaderReferences(assignments).forEach { reference ->
            resolveRelativePath(presetRelativePath, reference)?.let { shaderQueue.addLast(it) }
        }

        val shaderTexts = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        while (shaderQueue.isNotEmpty()) {
            val shaderPath = shaderQueue.removeFirst()
            if (!visited.add(shaderPath)) {
                continue
            }
            val shaderText = readText(shaderPath) ?: continue
            shaderTexts += shaderText
            includeReferences(shaderText).forEach { reference ->
                resolveRelativePath(shaderPath, reference)?.let { shaderQueue.addLast(it) }
            }
        }

        return requiresNativeDsSource(assignments, shaderTexts)
    }

    private fun requiresNativeDsSource(assignments: Map<String, String>, shaderTexts: List<String>): Boolean {
        if (shaderTexts.isEmpty()) {
            return false
        }

        val code = shaderTexts.joinToString(separator = "\n").lowercase()
        if (!code.contains("sourcesize") && !code.contains("originalsize") && !code.contains("outputsize")) {
            return false
        }

        val anyPassTargetsViewport = passTargetsViewport(assignments)
        val pixelGridToken = code.contains("texelfetch") ||
            code.contains("texelfetchoffset") ||
            code.contains("subpix") ||
            code.contains("retro_pixel") ||
            code.contains("pixel_size") ||
            code.contains("pixel size") ||
            code.contains("lcd gamma") ||
            code.contains("lcd grid") ||
            code.contains("scanline") ||
            code.contains("scanlines") ||
            code.contains("pixel_borders") ||
            code.contains("tx_to_px") ||
            code.contains("subpx_coverage")

        val sizeRatioToken = SourceOutputRatioRegex.containsMatchIn(code) ||
            OriginalOutputRatioRegex.containsMatchIn(code) ||
            OutputInputRatioRegex.containsMatchIn(code)
        val gridCoordinateToken = SourceGridRegex.containsMatchIn(code) ||
            OriginalGridRegex.containsMatchIn(code)

        return if (anyPassTargetsViewport) {
            pixelGridToken || sizeRatioToken || gridCoordinateToken
        } else {
            pixelGridToken && sizeRatioToken
        }
    }

    private fun passTargetsViewport(assignments: Map<String, String>): Boolean {
        val count = passCount(assignments)
        for (index in 0 until count) {
            val scaleType = assignments["scale_type$index"]?.lowercase()
            val scaleTypeX = assignments["scale_type_x$index"]?.lowercase()
            val scaleTypeY = assignments["scale_type_y$index"]?.lowercase()
            if (scaleType == "viewport" || scaleTypeX == "viewport" || scaleTypeY == "viewport") {
                return true
            }
        }
        return false
    }
}
