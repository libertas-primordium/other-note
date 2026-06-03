package com.libertasprimordium.othernote.web

data class WebAccountIdentity(
    val publicKeyHex: String,
) {
    val displayPublicKey: String =
        "${publicKeyHex.take(8)}...${publicKeyHex.takeLast(8)}"
}

sealed interface WebSignInState {
    data object SignedOut : WebSignInState
    data object SigningIn : WebSignInState
    data class SignedIn(val identity: WebAccountIdentity) : WebSignInState
    data class Failed(val message: String) : WebSignInState
}

data class WebAuthUiState(
    val nip07Available: Boolean,
    val signInState: WebSignInState = WebSignInState.SignedOut,
)

sealed interface Nip07PublicKeyResult {
    data class Valid(val publicKeyHex: String) : Nip07PublicKeyResult
    data class Invalid(val message: String) : Nip07PublicKeyResult
}

object WebAuthCopy {
    const val ExtensionMissing = "NIP-07 browser extension not found."
    const val PublicKeyMissing = "The extension did not return a public key."
    const val PublicKeyMalformed = "The extension returned an invalid public key."
    const val ExtensionRequestFailed = "The extension request was canceled or failed."
}

private val HexPublicKeyPattern = Regex("^[0-9a-fA-F]{64}$")

fun validateNip07PublicKey(publicKey: String?): Nip07PublicKeyResult {
    val trimmed = publicKey?.trim().orEmpty()
    if (trimmed.isBlank()) {
        return Nip07PublicKeyResult.Invalid(WebAuthCopy.PublicKeyMissing)
    }
    if (!HexPublicKeyPattern.matches(trimmed)) {
        return Nip07PublicKeyResult.Invalid(WebAuthCopy.PublicKeyMalformed)
    }
    return Nip07PublicKeyResult.Valid(trimmed.lowercase())
}

fun beginNip07SignIn(state: WebAuthUiState): WebAuthUiState =
    if (state.nip07Available) {
        state.copy(signInState = WebSignInState.SigningIn)
    } else {
        state.copy(signInState = WebSignInState.Failed(WebAuthCopy.ExtensionMissing))
    }

fun completeNip07SignIn(state: WebAuthUiState, publicKey: String?): WebAuthUiState =
    when (val result = validateNip07PublicKey(publicKey)) {
        is Nip07PublicKeyResult.Valid ->
            state.copy(signInState = WebSignInState.SignedIn(WebAccountIdentity(result.publicKeyHex)))
        is Nip07PublicKeyResult.Invalid ->
            state.copy(signInState = WebSignInState.Failed(result.message))
    }

fun failNip07SignIn(state: WebAuthUiState): WebAuthUiState =
    state.copy(signInState = WebSignInState.Failed(WebAuthCopy.ExtensionRequestFailed))

fun logoutWebAccount(state: WebAuthUiState): WebAuthUiState =
    state.copy(signInState = WebSignInState.SignedOut)
