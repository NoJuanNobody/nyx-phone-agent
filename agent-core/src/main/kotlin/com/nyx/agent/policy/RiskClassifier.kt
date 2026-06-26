package com.nyx.agent.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Classifies an action type into a [RiskTier].
 *
 * The classifier is the single source of truth for risk. The Policy Engine
 * uses the tier to decide whether a confirmation prompt is required in the
 * absence of an explicit policy rule.
 *
 * Misclassification is a test-failing defect: a destructive action rated
 * LOW would bypass consent — a critical safety failure.
 */
object RiskClassifier {

    private val tierMap: Map<String, RiskTier> = mapOf(
        // LOW — read-only / non-destructive
        "sms.read" to RiskTier.LOW,
        "calendar.read" to RiskTier.LOW,
        "contacts.read" to RiskTier.LOW,
        "notifications.read" to RiskTier.LOW,
        "system.query" to RiskTier.LOW,
        "system.ping" to RiskTier.LOW,
        "ui.screenshot" to RiskTier.LOW,
        "voice.stt" to RiskTier.LOW,
        // MEDIUM — reversible side effects
        "system.wifi" to RiskTier.MEDIUM,
        "system.bluetooth" to RiskTier.MEDIUM,
        "system.volume" to RiskTier.MEDIUM,
        "system.brightness" to RiskTier.MEDIUM,
        "notifications.dismiss" to RiskTier.MEDIUM,
        "app.launch" to RiskTier.MEDIUM,
        "ui.tap" to RiskTier.MEDIUM,
        "ui.scroll" to RiskTier.MEDIUM,
        // HIGH — destructive / irreversible / sensitive
        "sms.send" to RiskTier.HIGH,
        "telephony.answer" to RiskTier.HIGH,
        "telephony.reject" to RiskTier.HIGH,
        "telephony.hold" to RiskTier.HIGH,
        "calendar.create" to RiskTier.HIGH,
        "notifications.act" to RiskTier.HIGH,
        "system.setting" to RiskTier.HIGH
    )

    /** All action types known to the classifier (>= 20 per spec). */
    val knownActions: Set<String> get() = tierMap.keys

    /** Classify an action type. Unknown actions default to HIGH (fail-safe). */
    fun classify(actionType: String): RiskTier =
        tierMap[actionType] ?: RiskTier.HIGH
}
