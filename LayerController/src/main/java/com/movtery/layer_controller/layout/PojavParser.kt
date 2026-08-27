package com.movtery.layer_controller.layout

import android.util.DisplayMetrics
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.ButtonStyle
import com.movtery.layer_controller.data.DefaultDirectionEvents
import com.movtery.layer_controller.data.DefaultJoystickStyleConfig
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.JoystickStyle
import com.movtery.layer_controller.data.JoystickTriggerMode
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.lang.TranslatableString
import com.movtery.layer_controller.event.ClickEvent
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val POJAV_MARGIN_DP = 2f
private const val EDITOR_VERSION = 11
private val DEFAULT_PRESSED_BG = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.5f)
private val DEFAULT_FG = Color.White

private fun randomUUID(length: Int = 12): String =
    UUID.randomUUID().toString().replace("-", "").take(length)

fun isPojavJson(jsonString: String): Boolean =
    jsonString.contains("\"mControlDataList\"")

fun parsePojavLayout(jsonString: String): ControlLayout {
    val dm: DisplayMetrics
    try {
        dm = android.content.res.Resources.getSystem().displayMetrics
    } catch (_: Exception) {
        return ControlLayout(
            info = ControlLayout.Info(
                name = TranslatableString("Pojav", emptyList()),
                author = TranslatableString("PojavLauncherTeam", emptyList()),
                description = TranslatableString("PojavLauncher touch controls", emptyList()),
                versionCode = 1,
                versionName = "1.0"
            ),
            layers = emptyList(),
            styles = emptyList(),
            editorVersion = EDITOR_VERSION
        )
    }
    return convertPojavToZalith(jsonString, dm)
}

fun convertPojavToZalith(jsonString: String, displayMetrics: DisplayMetrics): ControlLayout {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonString).jsonObject

    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    val screenHeightDp = displayMetrics.heightPixels / displayMetrics.density

    val buttons = root["mControlDataList"]?.jsonArray ?: emptyList()
    val drawers = root["mDrawerDataList"]?.jsonArray ?: emptyList()
    val joysticks = root["mJoystickDataList"]?.jsonArray ?: emptyList()
    val infoData = root["mControlInfoDataList"]?.jsonObject

    val styleCache = mutableMapOf<String, ButtonStyle>()
    val joystickStyleCache = mutableMapOf<String, JoystickStyle>()
    val allButtons = mutableListOf<NormalData>()
    val allJoysticks = mutableListOf<JoystickData>()

    // Pojav historically writes scaledAt as a JSON number. Depending on Gson version
    // it may be emitted as either 100 or 100.0, so parse from content instead of
    // forcing JsonPrimitive.int. The value is a percentage.
    val preferredScale = root["scaledAt"]?.jsonPrimitive?.content
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.div(100f) ?: 1f

    for (btnObj in buttons) {
        convertPojavButton(btnObj, screenWidthDp, screenHeightDp, preferredScale)?.let { (data, style) ->
            allButtons.add(data)
            if (style != null) styleCache[style.uuid] = style
        }
    }

    for (drawerObj in drawers) {
        convertPojavDrawer(drawerObj, screenWidthDp, screenHeightDp, preferredScale)?.let { items ->
            for ((data, style) in items) {
                allButtons.add(data)
                if (style != null) styleCache[style.uuid] = style
            }
        }
    }

    for (joyObj in joysticks) {
        convertPojavJoystick(joyObj, screenWidthDp, screenHeightDp, preferredScale)?.let { (data, style) ->
            allJoysticks.add(data)
            if (style != null) joystickStyleCache[style.uuid] = style
        }
    }

    val author = infoData?.let { parseJsonString(it["author"]) } ?: "PojavLauncherTeam"
    val name = infoData?.let { parseJsonString(it["name"]) } ?: "Pojav"

    return ControlLayout(
        info = ControlLayout.Info(
            name = TranslatableString(name, emptyList()),
            author = TranslatableString(author, emptyList()),
            description = TranslatableString("PojavLauncher touch controls", emptyList()),
            versionCode = 1,
            versionName = "1.0"
        ),
        layers = listOf(
            ControlLayer(
                name = "controls",
                uuid = randomUUID(),
                hide = false,
                hideWhenMouse = true,
                hideWhenGamepad = true,
                visibilityType = VisibilityType.ALWAYS,
                normalButtons = allButtons,
                textBoxes = emptyList(),
                joystickButtons = allJoysticks
            )
        ),
        styles = styleCache.values.toList(),
        joystickStyles = joystickStyleCache.values.toList(),
        editorVersion = EDITOR_VERSION
    )
}

private fun convertPojavButton(
    btnObj: JsonElement,
    sw: Float, sh: Float, scale: Float
): Pair<NormalData, ButtonStyle?>? {
    val btn = btnObj.jsonObject
    val name = btn["name"]?.jsonPrimitive?.content ?: return null

    val w = ((btn["width"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 50f) * scale)
    val h = ((btn["height"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 50f) * scale)
    if (w <= 0 || h <= 0) return null

    val rawX = btn["dynamicX"]?.jsonPrimitive?.content ?: "\${margin}"
    val rawY = btn["dynamicY"]?.jsonPrimitive?.content ?: "\${margin}"

    if (rawX.contains("Infinity") || rawY.contains("Infinity")) return null

    val xDp = evalPojavExpr(rawX, w, h, sw, sh)
    val yDp = evalPojavExpr(rawY, w, h, sw, sh)

    val xPct = ((xDp / sw) * 10000).toInt().coerceIn(0, 10000)
    val yPct = ((yDp / sh) * 10000).toInt().coerceIn(0, 10000)

    val keycodes = btn["keycodes"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList()
    val events = keycodes.flatMap { pojavKeyToClickEvents(it) }.ifEmpty {
        listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_KEY_UNKNOWN"))
    }

    val displayInGame = btn["displayInGame"]?.jsonPrimitive?.boolean ?: true
    val displayInMenu = btn["displayInMenu"]?.jsonPrimitive?.boolean ?: true
    if (!displayInGame && !displayInMenu) return null

    val visibility = when {
        displayInGame && displayInMenu -> VisibilityType.ALWAYS
        displayInGame -> VisibilityType.IN_GAME
        else -> VisibilityType.IN_MENU
    }

    val opacity = (btn["opacity"]?.jsonPrimitive?.int ?: 100).coerceIn(0, 100) / 100f
    val bgColor = btn["bgColor"]?.jsonPrimitive?.long ?: 0x4D000000L
    val cornerRadius = (btn["cornerRadius"]?.jsonPrimitive?.int ?: 0).toFloat()
    val strokeWidth = (btn["strokeWidth"]?.jsonPrimitive?.int ?: 0).toFloat()
    val strokeColor = btn["strokeColor"]?.jsonPrimitive?.long ?: if (strokeWidth > 0) 0xFFFFFFFFL else 0L
    val isSwipeable = btn["isSwipeable"]?.jsonPrimitive?.boolean ?: false
    val isToggle = btn["isToggle"]?.jsonPrimitive?.boolean ?: false
    val isPenetrable = btn["passThruEnabled"]?.jsonPrimitive?.boolean ?: false

    val renderedRadius = cornerRadius.coerceIn(0f, 100f)
    val shape = ButtonShape(renderedRadius)

    val color = Color(
        red = ((bgColor shr 16) and 0xFF).toFloat() / 255f,
        green = ((bgColor shr 8) and 0xFF).toFloat() / 255f,
        blue = (bgColor and 0xFF).toFloat() / 255f,
        alpha = ((bgColor shr 24) and 0xFF).toFloat() / 255f
    )
    val borderColor = Color(
        red = ((strokeColor shr 16) and 0xFF).toFloat() / 255f,
        green = ((strokeColor shr 8) and 0xFF).toFloat() / 255f,
        blue = (strokeColor and 0xFF).toFloat() / 255f,
        alpha = ((strokeColor shr 24) and 0xFF).toFloat() / 255f
    )

    val styleUuid = makeStyleHash(
        opacity, bgColor, cornerRadius, strokeWidth, strokeColor
    )

    val data = NormalData(
        text = TranslatableString(name, emptyList()),
        uuid = randomUUID(),
        position = ButtonPosition(x = xPct, y = yPct),
        buttonSize = ButtonSize(
            type = ButtonSize.Type.Dp,
            widthDp = w.coerceAtLeast(5f),
            heightDp = h.coerceAtLeast(5f),
            widthPercentage = 1400,
            heightPercentage = 1400,
            widthReference = ButtonSize.Reference.ScreenHeight,
            heightReference = ButtonSize.Reference.ScreenHeight
        ),
        buttonStyle = styleUuid,
        textAlignment = TextAlignment.Center,
        visibilityType = visibility,
        _clickEvents = events,
        isSwipple = isSwipeable,
        isPenetrable = isPenetrable,
        isToggleable = isToggle
    )

    val style = ButtonStyle(
        name = name.take(16),
        uuid = styleUuid,
        animateSwap = false,
        commonStyle = true,
        lightStyle = ButtonStyle.StyleConfig(
            alpha = opacity,
            pressedAlpha = opacity,
            backgroundColor = color,
            pressedBackgroundColor = if (bgColor == 0L) DEFAULT_PRESSED_BG
                else darkenColor(color, 0.3f),
            contentColor = DEFAULT_FG,
            pressedContentColor = DEFAULT_FG,
            fontSize = null,
            pressedFontSize = null,
            borderWidth = strokeWidth.toInt(),
            pressedBorderWidth = strokeWidth.toInt(),
            borderColor = borderColor,
            pressedBorderColor = borderColor,
            borderRadius = shape,
            pressedBorderRadius = shape
        ),
        darkStyle = ButtonStyle.StyleConfig(
            alpha = opacity,
            pressedAlpha = opacity,
            backgroundColor = color,
            pressedBackgroundColor = if (bgColor == 0L) DEFAULT_PRESSED_BG
                else darkenColor(color, 0.3f),
            contentColor = DEFAULT_FG,
            pressedContentColor = DEFAULT_FG,
            fontSize = null,
            pressedFontSize = null,
            borderWidth = strokeWidth.toInt(),
            pressedBorderWidth = strokeWidth.toInt(),
            borderColor = borderColor,
            pressedBorderColor = borderColor,
            borderRadius = shape,
            pressedBorderRadius = shape
        )
    )

    return Pair(data, style)
}

private fun convertPojavDrawer(
    drawerObj: JsonElement,
    sw: Float, sh: Float, scale: Float
): List<Pair<NormalData, ButtonStyle?>>? {
    val drawer = drawerObj.jsonObject
    val properties = drawer["properties"]?.jsonObject ?: return null
    val orientation = drawer["orientation"]?.jsonPrimitive?.content ?: "LEFT"
    val buttonProps = drawer["buttonProperties"]?.jsonArray ?: return null

    val dx = properties["dynamicX"]?.jsonPrimitive?.content ?: "\${margin}"
    val dy = properties["dynamicY"]?.jsonPrimitive?.content ?: "\${margin}"
    val dw = ((properties["width"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 50f) * scale)
    val dh = ((properties["height"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 50f) * scale)
    if (dw <= 0 || dh <= 0) return null

    val dX = evalPojavExpr(dx, dw, dh, sw, sh)
    val dY = evalPojavExpr(dy, dw, dh, sw, sh)

    val result = mutableListOf<Pair<NormalData, ButtonStyle?>>()

    val drawerMain = convertPojavButton(properties, sw, sh, scale)
    if (drawerMain != null) {
        result.add(drawerMain)
    }

    for ((i, btnEl) in buttonProps.withIndex()) {
        val btn = convertPojavButton(btnEl, sw, sh, scale) ?: continue
        val btnData = btn.first
        val btnStyle = btn.second

        val offsetX = when (orientation.uppercase()) {
            "RIGHT" -> dw * (i + 1)
            "LEFT" -> -(i + 1) * dw
            else -> 0f
        }
        val offsetY = when (orientation.uppercase()) {
            "DOWN" -> dh * (i + 1)
            "UP" -> -(i + 1) * dh
            else -> 0f
        }

        val newXPct = ((dX + offsetX) / sw * 10000).toInt().coerceIn(0, 10000)
        val newYPct = ((dY + offsetY) / sh * 10000).toInt().coerceIn(0, 10000)

        result.add(
            btnData.copy(
                position = ButtonPosition(newXPct, newYPct)
            ) to btnStyle
        )
    }

    return result
}

private fun convertPojavJoystick(
    joyObj: JsonElement,
    sw: Float, sh: Float, scale: Float
): Pair<JoystickData, JoystickStyle?>? {
    val joy = joyObj.jsonObject
    val name = joy["name"]?.jsonPrimitive?.content ?: "Joystick"

    val w = ((joy["width"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 80f) * scale)
    val h = ((joy["height"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 80f) * scale)
    if (w <= 0 || h <= 0) return null

    val rawX = joy["dynamicX"]?.jsonPrimitive?.content ?: "\${margin}"
    val rawY = joy["dynamicY"]?.jsonPrimitive?.content ?: "\${margin}"

    if (rawX.contains("Infinity") || rawY.contains("Infinity")) return null

    val xDp = evalPojavExpr(rawX, w, h, sw, sh)
    val yDp = evalPojavExpr(rawY, w, h, sw, sh)

    val xPct = ((xDp / sw) * 10000).toInt().coerceIn(0, 10000)
    val yPct = ((yDp / sh) * 10000).toInt().coerceIn(0, 10000)

    val displayInGame = joy["displayInGame"]?.jsonPrimitive?.boolean ?: true
    val displayInMenu = joy["displayInMenu"]?.jsonPrimitive?.boolean ?: true
    if (!displayInGame && !displayInMenu) return null

    val visibility = when {
        displayInGame && displayInMenu -> VisibilityType.ALWAYS
        displayInGame -> VisibilityType.IN_GAME
        else -> VisibilityType.IN_MENU
    }

    val opacity = (joy["opacity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 100f)
        .coerceIn(0f, 100f) / 100f
    val bgColor = joy["bgColor"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0x4D000000L
    val strokeColor = joy["strokeColor"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0xFFFFFFFFL
    val strokeWidth = joy["strokeWidth"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
    val absolute = joy["absolute"]?.jsonPrimitive?.boolean ?: false
    val forwardLock = joy["forwardLock"]?.jsonPrimitive?.boolean ?: false

    val styleUuid = makeStyleHash(opacity, bgColor, 50f, strokeWidth, strokeColor)
    val sizeDp = maxOf(w, h).coerceAtLeast(20f)

    val data = JoystickData(
        uuid = randomUUID(),
        position = ButtonPosition(x = xPct, y = yPct),
        sizeType = ButtonSize.Type.Dp,
        sizeDp = sizeDp,
        sizePercentage = 2500,
        visibilityType = visibility,
        joystickStyleId = styleUuid,
        deadZoneRatio = 0.35f,
        lockThreshold = 0.6f,
        canLock = forwardLock,
        triggerMode = if (absolute) JoystickTriggerMode.TOUCH else JoystickTriggerMode.DRAG,
        directionEvents = DefaultDirectionEvents,
        lockEvents = if (forwardLock) {
            listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_KEY_LEFT_CONTROL"))
        } else {
            emptyList()
        }
    )

    val background = colorFromArgb(bgColor)
    val border = colorFromArgb(strokeColor)
    val styleConfig = DefaultJoystickStyleConfig.copy(
        alpha = opacity,
        backgroundColor = background,
        borderColor = border,
        borderWidthRatio = strokeWidth.toInt().coerceIn(0, 50),
        backgroundShape = 50,
        joystickShape = 50,
        joystickSize = 0.5f
    )
    val style = JoystickStyle(
        name = name.take(16),
        uuid = styleUuid,
        commonStyle = true,
        lightStyle = styleConfig,
        darkStyle = styleConfig
    )

    return data to style
}

private fun colorFromArgb(argb: Long): Color = Color(
    red = ((argb shr 16) and 0xFF).toFloat() / 255f,
    green = ((argb shr 8) and 0xFF).toFloat() / 255f,
    blue = (argb and 0xFF).toFloat() / 255f,
    alpha = ((argb shr 24) and 0xFF).toFloat() / 255f
)

private fun makeStyleHash(
    opacity: Float, bgColor: Long, cornerRadius: Float,
    strokeWidth: Float, strokeColor: Long
): String {
    val raw = "$opacity|$bgColor|$cornerRadius|$strokeWidth|$strokeColor"
    return "pojav_" + raw.hashCode().let {
        if (it == Int.MIN_VALUE) "default" else it.toString(36)
    }
}

private fun darkenColor(color: Color, factor: Float): Color {
    return Color(
        red = (color.red * (1f - factor)).coerceIn(0f, 1f),
        green = (color.green * (1f - factor)).coerceIn(0f, 1f),
        blue = (color.blue * (1f - factor)).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}

private fun parseJsonString(element: JsonElement?): String? {
    return when {
        element?.jsonPrimitive?.isString == true -> element.jsonPrimitive.content
        element?.jsonObject != null -> element.jsonObject["default"]?.jsonPrimitive?.content
        else -> null
    }
}

private fun evalPojavExpr(expr: String, btnW: Float, btnH: Float, sw: Float, sh: Float): Float {
    val margin = POJAV_MARGIN_DP
    var s = expr
    s = s.replace("\${margin}", margin.toString())
    s = s.replace("\${width}", btnW.toString())
    s = s.replace("\${height}", btnH.toString())
    s = s.replace("\${screen_width}", sw.toString())
    s = s.replace("\${screen_height}", sh.toString())
    s = s.replace("\${right}", (sw - btnW - margin).toString())
    s = s.replace("\${bottom}", (sh - btnH - margin).toString())
    s = s.replace("\${top}", "0")
    s = s.replace("\${left}", "0")
    s = s.replace("\${preferred_scale}", "1")
    s = s.replace(Regex("dp\\(([^)]+)\\)")) { match ->
        val inner = match.groupValues[1].trim()
        (evalPojavExpr(inner, btnW, btnH, sw, sh)).toString()
    }
    s = s.replace(Regex("px\\(([^)]+)\\)")) { match ->
        val inner = match.groupValues[1].trim()
        (evalPojavExpr(inner, btnW, btnH, sw, sh)).toString()
    }
    return evaluateSimple(s)
}

private fun evaluateSimple(expr: String): Float {
    val cleaned = expr.replace(" ", "")
    if (cleaned.all { it.isDigit() || it == '.' }) return cleaned.toFloat()

    return try {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (c in cleaned) {
            if (c in "+-*/()") {
                if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                tokens.add(c.toString())
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())

        ExprParser(tokens).parse()
    } catch (_: Exception) {
        0f
    }
}

private class ExprParser(private val tokens: List<String>) {
    private var idx = 0

    fun parse(): Float = parseExpr()

    private fun parseExpr(): Float {
        var result = parseTerm()
        while (idx < tokens.size && (tokens[idx] == "+" || tokens[idx] == "-")) {
            val op = tokens[idx++]
            val rhs = parseTerm()
            result = if (op == "+") result + rhs else result - rhs
        }
        return result
    }

    private fun parseTerm(): Float {
        var result = parseFactor()
        while (idx < tokens.size && (tokens[idx] == "*" || tokens[idx] == "/")) {
            val op = tokens[idx++]
            val rhs = parseFactor()
            result = if (op == "*") result * rhs else result / rhs
        }
        return result
    }

    private fun parseFactor(): Float {
        if (idx >= tokens.size) return 0f
        val t = tokens[idx]
        if (t == "(") { idx++; val v = parseExpr(); idx++; return v }
        if (t == "-") { idx++; return -parseFactor() }
        idx++
        return t.toFloatOrNull() ?: 0f
    }
}

private val GLFW_KEYCODE_EVENT_NAMES = mapOf(
    0 to "GLFW_KEY_UNKNOWN",
    32 to "GLFW_KEY_SPACE",
    39 to "GLFW_KEY_APOSTROPHE",
    44 to "GLFW_KEY_COMMA",
    45 to "GLFW_KEY_MINUS",
    46 to "GLFW_KEY_PERIOD",
    47 to "GLFW_KEY_SLASH",
    48 to "GLFW_KEY_0",
    49 to "GLFW_KEY_1",
    50 to "GLFW_KEY_2",
    51 to "GLFW_KEY_3",
    52 to "GLFW_KEY_4",
    53 to "GLFW_KEY_5",
    54 to "GLFW_KEY_6",
    55 to "GLFW_KEY_7",
    56 to "GLFW_KEY_8",
    57 to "GLFW_KEY_9",
    59 to "GLFW_KEY_SEMICOLON",
    61 to "GLFW_KEY_EQUAL",
    65 to "GLFW_KEY_A",
    66 to "GLFW_KEY_B",
    67 to "GLFW_KEY_C",
    68 to "GLFW_KEY_D",
    69 to "GLFW_KEY_E",
    70 to "GLFW_KEY_F",
    71 to "GLFW_KEY_G",
    72 to "GLFW_KEY_H",
    73 to "GLFW_KEY_I",
    74 to "GLFW_KEY_J",
    75 to "GLFW_KEY_K",
    76 to "GLFW_KEY_L",
    77 to "GLFW_KEY_M",
    78 to "GLFW_KEY_N",
    79 to "GLFW_KEY_O",
    80 to "GLFW_KEY_P",
    81 to "GLFW_KEY_Q",
    82 to "GLFW_KEY_R",
    83 to "GLFW_KEY_S",
    84 to "GLFW_KEY_T",
    85 to "GLFW_KEY_U",
    86 to "GLFW_KEY_V",
    87 to "GLFW_KEY_W",
    88 to "GLFW_KEY_X",
    89 to "GLFW_KEY_Y",
    90 to "GLFW_KEY_Z",
    91 to "GLFW_KEY_LEFT_BRACKET",
    92 to "GLFW_KEY_BACKSLASH",
    93 to "GLFW_KEY_RIGHT_BRACKET",
    96 to "GLFW_KEY_GRAVE_ACCENT",
    161 to "GLFW_KEY_WORLD_1",
    162 to "GLFW_KEY_WORLD_2",
    256 to "GLFW_KEY_ESCAPE",
    257 to "GLFW_KEY_ENTER",
    258 to "GLFW_KEY_TAB",
    259 to "GLFW_KEY_BACKSPACE",
    260 to "GLFW_KEY_INSERT",
    261 to "GLFW_KEY_DELETE",
    262 to "GLFW_KEY_RIGHT",
    263 to "GLFW_KEY_LEFT",
    264 to "GLFW_KEY_DOWN",
    265 to "GLFW_KEY_UP",
    266 to "GLFW_KEY_PAGE_UP",
    267 to "GLFW_KEY_PAGE_DOWN",
    268 to "GLFW_KEY_HOME",
    269 to "GLFW_KEY_END",
    280 to "GLFW_KEY_CAPS_LOCK",
    281 to "GLFW_KEY_SCROLL_LOCK",
    282 to "GLFW_KEY_NUM_LOCK",
    283 to "GLFW_KEY_PRINT_SCREEN",
    284 to "GLFW_KEY_PAUSE",
    290 to "GLFW_KEY_F1",
    291 to "GLFW_KEY_F2",
    292 to "GLFW_KEY_F3",
    293 to "GLFW_KEY_F4",
    294 to "GLFW_KEY_F5",
    295 to "GLFW_KEY_F6",
    296 to "GLFW_KEY_F7",
    297 to "GLFW_KEY_F8",
    298 to "GLFW_KEY_F9",
    299 to "GLFW_KEY_F10",
    300 to "GLFW_KEY_F11",
    301 to "GLFW_KEY_F12",
    302 to "GLFW_KEY_F13",
    303 to "GLFW_KEY_F14",
    304 to "GLFW_KEY_F15",
    305 to "GLFW_KEY_F16",
    306 to "GLFW_KEY_F17",
    307 to "GLFW_KEY_F18",
    308 to "GLFW_KEY_F19",
    309 to "GLFW_KEY_F20",
    310 to "GLFW_KEY_F21",
    311 to "GLFW_KEY_F22",
    312 to "GLFW_KEY_F23",
    313 to "GLFW_KEY_F24",
    314 to "GLFW_KEY_F25",
    320 to "GLFW_KEY_KP_0",
    321 to "GLFW_KEY_KP_1",
    322 to "GLFW_KEY_KP_2",
    323 to "GLFW_KEY_KP_3",
    324 to "GLFW_KEY_KP_4",
    325 to "GLFW_KEY_KP_5",
    326 to "GLFW_KEY_KP_6",
    327 to "GLFW_KEY_KP_7",
    328 to "GLFW_KEY_KP_8",
    329 to "GLFW_KEY_KP_9",
    330 to "GLFW_KEY_KP_DECIMAL",
    331 to "GLFW_KEY_KP_DIVIDE",
    332 to "GLFW_KEY_KP_MULTIPLY",
    333 to "GLFW_KEY_KP_SUBTRACT",
    334 to "GLFW_KEY_KP_ADD",
    335 to "GLFW_KEY_KP_ENTER",
    336 to "GLFW_KEY_KP_EQUAL",
    340 to "GLFW_KEY_LEFT_SHIFT",
    341 to "GLFW_KEY_LEFT_CONTROL",
    342 to "GLFW_KEY_LEFT_ALT",
    343 to "GLFW_KEY_LEFT_SUPER",
    344 to "GLFW_KEY_RIGHT_SHIFT",
    345 to "GLFW_KEY_RIGHT_CONTROL",
    346 to "GLFW_KEY_RIGHT_ALT",
    347 to "GLFW_KEY_RIGHT_SUPER",
    348 to "GLFW_KEY_MENU"
)

private fun pojavKeyToClickEvents(keycode: Int): List<ClickEvent> {
    return when (keycode) {
        -1 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.switch_ime"))
        -2 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.switch_menu"))
        -3 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "GLFW_MOUSE_BUTTON_LEFT"))
        -4 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "GLFW_MOUSE_BUTTON_RIGHT"))
        -5 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.switch_menu"))
        -6 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "GLFW_MOUSE_BUTTON_MIDDLE"))
        -7 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.scroll_up"))
        -8 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.scroll_down"))
        -9 -> listOf(ClickEvent(ClickEvent.Type.LauncherEvent, "launcher.event.switch_menu"))
        else -> listOf(ClickEvent(ClickEvent.Type.Key, GLFW_KEYCODE_EVENT_NAMES[keycode] ?: "GLFW_KEY_UNKNOWN"))
    }
}
