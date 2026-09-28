package com.vrticconnect.modules.mail

import org.slf4j.LoggerFactory

enum class MailKind { INVITATION, EMAIL_VERIFICATION, PASSWORD_RESET, SECURITY_ALERT }

/**
 * Transactional e-mail. `link` carries the one-time token; the body is built by the sender from
 * localized templates (E11). Nothing here is ever written to the application log except in the
 * DEV-only [LoggingMailSender].
 */
data class MailMessage(
    val to: String,
    val kind: MailKind,
    val locale: String,
    val link: String?,
    val subjectKey: String,
    val args: Map<String, String> = emptyMap(),
)

/** E02-B20: adapter boundary. Production uses an SES implementation (EPIC 18); DEV logs the link. */
fun interface MailSender {
    fun send(message: MailMessage)
}

/**
 * DEV ONLY: writes the recipient, kind and link to a dedicated logger so a developer can click
 * the link from the console. Never wired outside APP_ENV=dev (see Main/AppDependencies).
 */
class LoggingMailSender : MailSender {
    private val log = LoggerFactory.getLogger("com.vrticconnect.mail.DEV")
    override fun send(message: MailMessage) {
        log.info("DEV MAIL kind={} to={} locale={} link={}", message.kind, message.to, message.locale, message.link ?: "-")
    }
}

/**
 * STAGING/PRODUCTION default until the SES adapter is configured: refuses to send so a missing
 * provider is a loud configuration error, never a silently dropped reset link.
 */
class UnconfiguredMailSender : MailSender {
    override fun send(message: MailMessage) {
        throw IllegalStateException("No MailSender configured for this environment (MAIL_PROVIDER); cannot send ${message.kind}")
    }
}
