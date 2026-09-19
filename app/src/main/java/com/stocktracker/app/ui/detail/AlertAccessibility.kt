package com.stocktracker.app.ui.detail

/**
 * What TalkBack should say for one alert's arm/disarm switch.
 *
 * The switch itself sits as a sibling of the Text that names the alert ("Crosses above", "Falls
 * below", ...), not merged with it — a raw `Switch` there announces only "Switch, off", which tells
 * a screen-reader user nothing about which of the four price alerts they just landed on, or whether
 * there is even a level to arm. This is the [androidx.compose.ui.semantics.stateDescription] half of
 * that fix: the label goes in `contentDescription` at the call site, and this is the state.
 *
 * PLAT-4.
 */
fun alertLevelSwitchStateDescription(level: Double?, armed: Boolean, format: (Double) -> String): String =
    when {
        level == null -> "not set"
        armed -> "armed at ${format(level)}"
        else -> "off, level ${format(level)} kept"
    }

/** Same idea for the condition alerts (SEC filing, insider buy, etc.) — no numeric level, just on/off. */
fun alertConditionSwitchStateDescription(armed: Boolean): String = if (armed) "armed" else "off"
