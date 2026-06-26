package com.nyx.agent.skill.impl

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager

/**
 * Production [SmsBridge] backed by Android SMS ContentProvider and [SmsManager].
 */
class AndroidSmsBridge(private val context: Context) : SmsBridge {
    override fun readThreads(limit: Int): List<SmsThread> {
        // Query content://sms with SORT timestamp DESC, LIMIT limit
        // Group by address into SmsThread, resolve contact name
        // Return list
        val threads = mutableMapOf<String, MutableList<SmsMessage>>()
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("address", "body", "date", "type"),
            null, null, "date DESC LIMIT $limit"
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val dateIdx = it.getColumnIndex("date")
            val typeIdx = it.getColumnIndex("type")
            while (it.moveToNext()) {
                val address = it.getString(addressIdx) ?: continue
                val msg = SmsMessage(
                    body = it.getString(bodyIdx) ?: "",
                    isIncoming = it.getInt(typeIdx) == 1,
                    timestampMs = it.getLong(dateIdx),
                )
                threads.getOrPut(address) { mutableListOf() }.add(msg)
            }
        }
        return threads.map { (address, msgs) ->
            SmsThread(contactName = resolveContact(address), phoneNumber = address, messages = msgs)
        }
    }

    override fun sendSms(toNumber: String, body: String) {
        @Suppress("DEPRECATION")
        SmsManager.getDefault().sendTextMessage(toNumber, null, body, null, null)
    }

    override fun resolveContact(phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }
}
