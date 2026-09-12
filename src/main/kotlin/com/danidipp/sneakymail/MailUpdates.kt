package com.danidipp.sneakymail

// Returns the record to notify about when an update makes mail available again.
internal fun updateCachedMail(
    mail: MutableMap<String, MailRecord>,
    mailRecord: MailRecord
): MailRecord? {
    val id = requireNotNull(mailRecord.id)
    val oldRecord = mail[id]
    if (oldRecord != null && !oldRecord.available && mailRecord.available) {
        oldRecord.apply {
            available = mailRecord.available
            sender_uuid = mailRecord.sender_uuid
            sender_name = mailRecord.sender_name
            recipient_uuid = mailRecord.recipient_uuid
            recipient_name = mailRecord.recipient_name
            rewards = mailRecord.rewards
            note = mailRecord.note
        }
        return oldRecord
    }
    mail[id] = mailRecord
    return null
}
