package com.libertasprimordium.othernote.web

data class WebAccountIdentity(
    val publicKeyHex: String,
    val method: WebAuthMethod,
) {
    val displayPublicKey: String =
        "${publicKeyHex.take(8)}...${publicKeyHex.takeLast(8)}"
}

enum class WebAuthMethod(val displayName: String) {
    Nip07("NIP-07 browser extension"),
    Nip46("NIP-46 remote signer"),
    DirectNsec("Session-only direct nsec"),
}

sealed interface WebSignInState {
    data object SignedOut : WebSignInState
    data class SigningIn(val method: WebAuthMethod) : WebSignInState
    data class SignedIn(val identity: WebAccountIdentity) : WebSignInState
    data class Failed(val message: String, val method: WebAuthMethod? = null) : WebSignInState
}

enum class WebNip46Status {
    Idle,
    PreparingConnection,
    WaitingForSigner,
    RequestingPublicKey,
    Failed,
}

data class WebAuthUiState(
    val nip07Available: Boolean,
    val signInState: WebSignInState = WebSignInState.SignedOut,
    val nip46Status: WebNip46Status = WebNip46Status.Idle,
    val nip46Message: String = "",
)

enum class WebMenuPanel {
    None,
    NoteRelays,
    Theme,
    About,
}

enum class WebSignInInfoTopic {
    Nip07,
    Nip46,
    RememberedNip46,
    DirectNsec,
    GeneratedIdentity,
}

data class WebSignInInfoCopy(
    val title: String,
    val body: List<String>,
)

val WebSignInInfoTopics = listOf(
    WebSignInInfoTopic.Nip07,
    WebSignInInfoTopic.Nip46,
    WebSignInInfoTopic.RememberedNip46,
    WebSignInInfoTopic.DirectNsec,
    WebSignInInfoTopic.GeneratedIdentity,
)

fun webSignInInfoCopy(topic: WebSignInInfoTopic): WebSignInInfoCopy =
    when (topic) {
        WebSignInInfoTopic.Nip07 -> WebSignInInfoCopy(
            title = "NIP-07 browser extension",
            body = listOf(
                "A NIP-07 browser extension signs, encrypts, and decrypts on your behalf.",
                "Other Note asks the extension for approved operations and does not receive your private key.",
            ),
        )
        WebSignInInfoTopic.Nip46 -> WebSignInInfoCopy(
            title = "NIP-46 remote signer",
            body = listOf(
                "A remote signer or bunker holds your private key while Other Note sends NIP-46 signer requests.",
                "Remote signer relays are separate from note relays and are used only for signer request traffic.",
                "The remote signer may see plaintext note payloads during encryption and decryption operations by design.",
            ),
        )
        WebSignInInfoTopic.RememberedNip46 -> WebSignInInfoCopy(
            title = "Remembered remote signer",
            body = listOf(
                "Remembering a remote signer does not store your private key.",
                "It stores Other Note's NIP-46 communication session record for this browser.",
                "That record is still sensitive because it can request approved signer actions until you forget it here or the signer revokes it.",
            ),
        )
        WebSignInInfoTopic.DirectNsec -> WebSignInInfoCopy(
            title = "Session-only nsec",
            body = listOf(
                "The nsec is your private key.",
                "Other Note does not save it, and refreshing or logging out forgets this session.",
                "If you explicitly allow browser/password-manager saving, that storage is controlled by your browser or password manager, not by Other Note.",
                "Password-manager prompts vary by browser and should be used only with a trusted password manager or browser profile.",
                "Use this only on trusted devices. Prefer NIP-07 or a remote signer on shared or untrusted browsers.",
            ),
        )
        WebSignInInfoTopic.GeneratedIdentity -> WebSignInInfoCopy(
            title = "Create new identity",
            body = listOf(
                "This creates a new Nostr identity and the generated nsec is the private key.",
                "Other Note cannot recover it. Losing it means losing access to encrypted notes for that identity forever.",
                "Save it securely before using the identity for notes.",
            ),
        )
    }

data class WebMenuUiState(
    val open: Boolean = false,
    val activePanel: WebMenuPanel = WebMenuPanel.None,
)

data class WebNoteLoadRequest(
    val generation: Int,
    val accountPubkey: String,
    val method: WebAuthMethod,
)

data class WebNoteLoadStart(
    val guard: WebNoteLoadGuard,
    val request: WebNoteLoadRequest,
)

data class WebNoteLoadGuard(
    val generation: Int = 0,
) {
    fun start(identity: WebAccountIdentity): WebNoteLoadStart {
        val next = copy(generation = generation + 1)
        return WebNoteLoadStart(
            guard = next,
            request = WebNoteLoadRequest(
                generation = next.generation,
                accountPubkey = identity.publicKeyHex,
                method = identity.method,
            ),
        )
    }

    fun invalidate(): WebNoteLoadGuard =
        copy(generation = generation + 1)

    fun accepts(request: WebNoteLoadRequest, authState: WebAuthUiState): Boolean {
        val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
        return request.generation == generation &&
            request.accountPubkey == signedIn.identity.publicKeyHex &&
            request.method == signedIn.identity.method
    }
}

data class WebNoteDetailUiState(
    val openNoteId: String? = null,
)

fun openWebNoteDetail(noteId: String): WebNoteDetailUiState =
    WebNoteDetailUiState(openNoteId = noteId)

fun closeWebNoteDetail(): WebNoteDetailUiState =
    WebNoteDetailUiState()

val WebSignedInMenuItems = listOf(
    "Reload notes",
    "Note relays",
    "Theme",
    "About Other Note Web",
    "Logout",
)

const val WebSignedOutShellClass = "shell"
const val WebSignedInShellClass = "shell signed-in-shell"
const val WebNotesPanelClass = "panel notes-panel"
const val WebNoteGridClass = "note-list note-lanes"
const val WebNoteLaneClass = "note-lane"
const val WebNoteCardActionsClass = "inline-actions note-card-actions"
const val WebNoteGridMinCardWidthPx = 280
const val WebNoteGridGapPx = 6
const val WebNoteGridMaxColumns = 6

fun webNoteLaneCount(availableWidthPx: Int): Int =
    when {
        availableWidthPx < 320 -> 1
        availableWidthPx < 720 -> 2
        else -> (availableWidthPx / WebNoteGridMinCardWidthPx).coerceIn(2, WebNoteGridMaxColumns)
    }

fun <T> distributeWebNoteLanes(items: List<T>, laneCount: Int): List<List<T>> {
    val safeLaneCount = laneCount.coerceAtLeast(1)
    val lanes = List(safeLaneCount) { mutableListOf<T>() }
    items.forEachIndexed { index, item ->
        lanes[index % safeLaneCount] += item
    }
    return lanes
}

sealed interface Nip07PublicKeyResult {
    data class Valid(val publicKeyHex: String) : Nip07PublicKeyResult
    data class Invalid(val message: String) : Nip07PublicKeyResult
}

object WebAuthCopy {
    const val ExtensionMissing = "NIP-07 browser extension not found."
    const val PublicKeyMissing = "The extension did not return a public key."
    const val PublicKeyMalformed = "The extension returned an invalid public key."
    const val ExtensionRequestFailed = "The extension request was canceled or failed."
    const val Nip46InvalidToken = "Paste a valid bunker:// remote signer token."
    const val Nip46MissingRelay = "Remote signer token must include at least one signer relay."
    const val Nip46InvalidRemotePubkey = "Remote signer token has an invalid signer public key."
    const val Nip46SignerTimeout = "Remote signer did not respond before the timeout."
    const val Nip46SignerRejected = "Remote signer rejected the request."
    const val Nip46MalformedResponse = "Remote signer returned an unreadable response."
    const val Nip46ConnectionFailed = "Could not connect to the remote signer relay."
    const val Nip46RelayPublishFailed = "Could not publish the remote signer request."
    const val Nip46RelayClosedBeforeResponse = "Remote signer relay closed before a response arrived."
    const val Nip46PublicKeyTimeout = "Remote signer did not answer the account public-key request before the timeout."
    const val Nip46PublicKeyRelayClosed = "Remote signer relay closed before the account public-key response arrived."
    const val Nip46TransportKeyFailed = "Could not create an in-memory remote signer transport key."
    const val Nip46RequestBuildFailed = "Could not create the remote signer request."
    const val Nip46RequestMissingRemoteSigner = "Remote signer request is missing a valid signer identity."
    const val Nip46RequestMissingClientKey = "Remote signer request is missing the in-memory transport identity."
    const val Nip46RequestJsonFailed = "Could not serialize the remote signer request."
    const val Nip46RequestEncryptionFailed = "Could not encrypt the remote signer request."
    const val Nip46RequestSigningFailed = "Could not sign the remote signer transport request."
    const val Nip46RequestSerializationFailed = "Could not serialize the remote signer event."
    const val Nip46ResponseDecryptFailed = "Could not decrypt the remote signer response."
    const val Nip46EmptyResponse = "Remote signer returned an empty response."
    const val Nip46BrowserCryptoMissing = "Browser secure random generation is unavailable."
    const val Nip46RandomGenerationFailed = "Browser secure random generation failed."
    const val Nip46InvalidGeneratedPrivateKey = "Browser generated an invalid remote signer transport key."
    const val Nip46PublicKeyDerivationFailed = "Could not derive the remote signer transport public key."
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
        state.copy(signInState = WebSignInState.SigningIn(WebAuthMethod.Nip07))
    } else {
        state.copy(signInState = WebSignInState.Failed(WebAuthCopy.ExtensionMissing, WebAuthMethod.Nip07))
    }

fun completeNip07SignIn(state: WebAuthUiState, publicKey: String?): WebAuthUiState =
    when (val result = validateNip07PublicKey(publicKey)) {
        is Nip07PublicKeyResult.Valid ->
            state.copy(signInState = WebSignInState.SignedIn(WebAccountIdentity(result.publicKeyHex, WebAuthMethod.Nip07)))
        is Nip07PublicKeyResult.Invalid ->
            state.copy(signInState = WebSignInState.Failed(result.message))
    }

fun failNip07SignIn(state: WebAuthUiState): WebAuthUiState =
    state.copy(signInState = WebSignInState.Failed(WebAuthCopy.ExtensionRequestFailed, WebAuthMethod.Nip07))

fun beginNip46SignIn(state: WebAuthUiState): WebAuthUiState =
    state.copy(
        signInState = WebSignInState.SigningIn(WebAuthMethod.Nip46),
        nip46Status = WebNip46Status.PreparingConnection,
        nip46Message = "Preparing remote signer connection.",
    )

fun updateNip46Progress(state: WebAuthUiState, status: WebNip46Status, message: String): WebAuthUiState =
    state.copy(nip46Status = status, nip46Message = message)

fun completeNip46SignIn(state: WebAuthUiState, publicKey: String?): WebAuthUiState =
    when (val result = validateNip07PublicKey(publicKey)) {
        is Nip07PublicKeyResult.Valid ->
            state.copy(
                signInState = WebSignInState.SignedIn(WebAccountIdentity(result.publicKeyHex, WebAuthMethod.Nip46)),
                nip46Status = WebNip46Status.Idle,
                nip46Message = "",
            )
        is Nip07PublicKeyResult.Invalid ->
            failNip46SignIn(state, result.message)
    }

fun failNip46SignIn(state: WebAuthUiState, message: String): WebAuthUiState =
    state.copy(
        signInState = WebSignInState.Failed(message, WebAuthMethod.Nip46),
        nip46Status = WebNip46Status.Failed,
        nip46Message = message,
    )

fun logoutWebAccount(state: WebAuthUiState): WebAuthUiState =
    state.copy(
        signInState = WebSignInState.SignedOut,
        nip46Status = WebNip46Status.Idle,
        nip46Message = "",
    )

fun toggleWebMenu(state: WebMenuUiState): WebMenuUiState =
    state.copy(open = !state.open)

fun closeWebMenu(state: WebMenuUiState): WebMenuUiState =
    state.copy(open = false)

fun openWebMenuPanel(state: WebMenuUiState, panel: WebMenuPanel): WebMenuUiState =
    state.copy(open = false, activePanel = panel)

fun closeWebMenuPanel(state: WebMenuUiState): WebMenuUiState =
    state.copy(activePanel = WebMenuPanel.None)

fun resetWebMenuState(): WebMenuUiState =
    WebMenuUiState()
