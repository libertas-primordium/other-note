package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.OtherNoteTag
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrRelayMessage
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.profileMetadataFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NostrWireJsonTests {
    private val event = NostrEvent(
        id = "id123",
        pubkey = "pub123",
        createdAt = 123,
        kind = 30078,
        tags = listOf(listOf("d", noteDTag("abc")), listOf("t", OtherNoteTag)),
        content = "ciphertext",
        sig = "sig123",
    )

    @Test
    fun publishEventMessageUsesNip01FieldNames() {
        val message = NostrWireJson.publishEventMessage(event)
        assertTrue(message.startsWith("""["EVENT","""))
        assertTrue(message.contains(""""created_at":123"""))
        assertTrue(message.contains(""""pubkey":"pub123""""))
        assertTrue(message.contains(""""kind":30078"""))
    }

    @Test
    fun requestFilterIncludesAuthorKindTagAndLimit() {
        val message = NostrWireJson.requestMessage(
            subscriptionId = "sub1",
            filter = NostrFilter(authors = listOf("pub123"), limit = 50),
        )
        assertEquals(
            """["REQ","sub1",{"authors":["pub123"],"kinds":[30078],"#t":["other-note"],"limit":50}]""",
            message,
        )
    }

    @Test
    fun fallbackRequestFilterKeepsAuthorAndKindButOmitsTagConstraint() {
        val message = NostrWireJson.requestMessage(
            subscriptionId = "sub1",
            filter = NostrFilter(authors = listOf("pub123"), tTags = emptyList(), limit = 1000),
        )
        assertEquals(
            """["REQ","sub1",{"authors":["pub123"],"kinds":[30078],"limit":1000}]""",
            message,
        )
    }

    @Test
    fun profileRequestFilterUsesKindZeroWithoutOtherNoteTag() {
        val message = NostrWireJson.requestMessage(
            subscriptionId = "sub1",
            filter = profileMetadataFilter("pub123", limit = 20),
        )

        assertEquals(
            """["REQ","sub1",{"authors":["pub123"],"kinds":[0],"limit":20}]""",
            message,
        )
    }

    @Test
    fun closeMessageSerializesSubscriptionId() {
        assertEquals("""["CLOSE","sub1"]""", NostrWireJson.closeMessage("sub1"))
    }

    @Test
    fun okResponsesParseAcceptedAndRejected() {
        val accepted = assertIs<NostrRelayMessage.Ok>(NostrWireJson.parseRelayMessage("""["OK","id123",true,""]"""))
        assertEquals("id123", accepted.eventId)
        assertEquals(true, accepted.accepted)

        val rejected = assertIs<NostrRelayMessage.Ok>(NostrWireJson.parseRelayMessage("""["OK","id123",false,"blocked: no"]"""))
        assertEquals(false, rejected.accepted)
        assertEquals("blocked: no", rejected.message)
    }

    @Test
    fun closedAndNoticeResponsesParseMessages() {
        val closed = assertIs<NostrRelayMessage.Closed>(NostrWireJson.parseRelayMessage("""["CLOSED","sub1","unsupported"]"""))
        assertEquals("sub1", closed.subscriptionId)
        assertEquals("unsupported", closed.message)

        val notice = assertIs<NostrRelayMessage.Notice>(NostrWireJson.parseRelayMessage("""["NOTICE","rate limited"]"""))
        assertEquals("rate limited", notice.message)
    }

    @Test
    fun eventAndEoseResponsesParseSubscriptionAndEvent() {
        val eventMessage = assertIs<NostrRelayMessage.Event>(
            NostrWireJson.parseRelayMessage("""["EVENT","sub1",${NostrWireJson.eventObject(event)}]"""),
        )
        assertEquals("sub1", eventMessage.subscriptionId)
        assertEquals(event, eventMessage.event)

        val eose = assertIs<NostrRelayMessage.Eose>(NostrWireJson.parseRelayMessage("""["EOSE","sub1"]"""))
        assertEquals("sub1", eose.subscriptionId)
    }
}
