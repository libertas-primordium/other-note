package com.libertasprimordium.othernote.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebAuthStateTests {
    @Test
    fun missingNip07SignerFailsSafely() {
        val state = beginNip07SignIn(WebAuthUiState(nip07Available = false))
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.ExtensionMissing, failed.message)
    }

    @Test
    fun validPublicKeySignsInInMemoryState() {
        val key = "A1".repeat(32)
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), key)
        val signedIn = assertIs<WebSignInState.SignedIn>(state.signInState)
        assertEquals(key.lowercase(), signedIn.identity.publicKeyHex)
        assertEquals("a1a1a1a1...a1a1a1a1", signedIn.identity.displayPublicKey)
    }

    @Test
    fun logoutClearsSignedInState() {
        val signedIn = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("01".repeat(32))),
        )
        assertIs<WebSignInState.SignedOut>(logoutWebAccount(signedIn).signInState)
    }

    @Test
    fun blankPublicKeyFailsSafely() {
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), "   ")
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.PublicKeyMissing, failed.message)
    }

    @Test
    fun malformedPublicKeyFailsSafely() {
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), "npub-not-a-hex-key")
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.PublicKeyMalformed, failed.message)
    }

    @Test
    fun extensionFailureMapsToSafeMessage() {
        val state = failNip07SignIn(WebAuthUiState(nip07Available = true))
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.ExtensionRequestFailed, failed.message)
    }
}
