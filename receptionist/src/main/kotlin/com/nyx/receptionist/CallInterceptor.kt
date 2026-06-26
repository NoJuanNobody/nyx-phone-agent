package com.nyx.receptionist

import android.telecom.Call
import android.telecom.InCallService

/**
 * TelecomManager InCallService that intercepts inbound calls for the AI Receptionist.
 *
 * When a call arrives, [CallInterceptor] answers it, starts the voice pipeline,
 * and routes the caller's intent to the appropriate [PersonaConfig].
 *
 * Must be declared in AndroidManifest with:
 *   <service android:name=".receptionist.CallInterceptor"
 *             android:permission="android.permission.BIND_INCALL_SERVICE">
 *     <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="true"/>
 *   </service>
 */
class CallInterceptor : InCallService() {
    var onCallAnswered: ((call: Call) -> Unit)? = null
    var onCallEnded: ((call: Call) -> Unit)? = null

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // Auto-answer the call
        call.answer(0)
        onCallAnswered?.invoke(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        onCallEnded?.invoke(call)
    }
}
