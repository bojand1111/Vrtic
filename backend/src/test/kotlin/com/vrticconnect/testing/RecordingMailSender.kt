package com.vrticconnect.testing

import com.vrticconnect.modules.mail.MailMessage
import com.vrticconnect.modules.mail.MailSender
import java.net.URLDecoder

/** Captures outgoing mail in tests so the one-time token can be read back without any logging. */
class RecordingMailSender : MailSender {
    val sent = mutableListOf<MailMessage>()

    override fun send(message: MailMessage) {
        sent += message
    }

    fun lastTokenFor(to: String): String? =
        sent.lastOrNull { it.to.equals(to, ignoreCase = true) }?.link
            ?.substringAfter("token=", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
}
