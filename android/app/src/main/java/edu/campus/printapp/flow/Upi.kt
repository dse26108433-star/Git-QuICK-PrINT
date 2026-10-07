package edu.campus.printapp.flow

/**
 * XeoGo Pay on the phone: what a UPI app answers when it closes, and the
 * UPI reference number (UTR) students type.
 *
 * The answer (NPCI UPI linking spec) looks like
 *   "txnId=ICI123&responseCode=00&Status=SUCCESS&txnRef=CPK7M4X...&ApprovalRefNo=627312345678"
 * It is never proof of payment (the app signs nothing): the server pays the
 * order only when the bank's message or the counter confirms the money. The
 * reference it carries just saves the student typing it.
 */
data class UpiAnswer(val status: Status, val reference: String?) {

    enum class Status { SUCCESS, SUBMITTED, FAILURE, UNKNOWN }

    companion object {
        private val TWELVE = Regex("(?<![0-9])[0-9]{12}(?![0-9])")

        fun parse(response: String?): UpiAnswer {
            if (response.isNullOrBlank()) return UpiAnswer(Status.UNKNOWN, null)
            val fields = response.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim().lowercase() to part.substring(i + 1).trim()
            }.toMap()
            val status = when (fields["status"]?.lowercase()) {
                "success", "s" -> Status.SUCCESS
                "submitted", "pending" -> Status.SUBMITTED
                "failure", "failed", "f" -> Status.FAILURE
                else -> Status.UNKNOWN
            }
            val approval = fields["approvalrefno"]?.takeIf { TWELVE.matches(it) }
            val any = fields.filterKeys { it != "txnref" }.values.firstNotNullOfOrNull { v -> TWELVE.find(v)?.value }
            return UpiAnswer(status, approval ?: any)
        }
    }
}

object UpiRef {
    /** "6273 1234-5678" -> "627312345678". Anything else stays as typed (the server refuses it). */
    fun clean(typed: String?): String {
        val s = typed?.trim() ?: return ""
        val digits = s.replace(Regex("[\\s-]"), "")
        return if (digits.all { it.isDigit() }) digits else s
    }

    /** Empty (not known) or exactly 12 digits. */
    fun ok(typed: String?): Boolean {
        val r = clean(typed)
        return r.isEmpty() || (r.length == 12 && r.all { it.isDigit() })
    }

    /** "627312345678" -> "6273 1234 5678". */
    fun group(ref: String?): String =
        if (ref != null && ref.length == 12 && ref.all { it.isDigit() }) ref.chunked(4).joinToString(" ") else ref ?: ""

    fun paiseWords(n: Int): String = "$n " + if (n == 1) "paisa" else "paise"
}
