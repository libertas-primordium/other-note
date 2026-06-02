package com.libertasprimordium.othernote.ui

enum class SignInOptionKind {
    AndroidSigner,
    RemoteSigner,
    ExistingNsec,
    CreateIdentity,
}

enum class SignInOptionEmphasis {
    Primary,
    Secondary,
    Low,
    Text,
}

data class SignInOptionUi(
    val kind: SignInOptionKind,
    val label: String,
    val supportingCopy: String,
    val emphasis: SignInOptionEmphasis,
    val enabled: Boolean = true,
)

enum class SignInInfoTopic {
    AndroidSigner,
    RemoteSigner,
    ExistingNsec,
    CreateIdentity,
    LocalOnly,
    DesktopKeyring,
}

data class SignInInfoCopy(
    val title: String,
    val body: String,
)

fun signInInfoCopy(topic: SignInInfoTopic): SignInInfoCopy =
    when (topic) {
        SignInInfoTopic.AndroidSigner -> SignInInfoCopy(
            title = "Android signer",
            body = "Your private key stays in the signer app. Other Note remembers the approved signer session so it can reopen without asking Amber just to sign in. Log out ends automatic sign-in. Forget removes only this device's saved signer metadata.",
        )
        SignInInfoTopic.RemoteSigner -> SignInInfoCopy(
            title = "Remote signer",
            body = "Your private key stays in the remote signer or bunker. Other Note stores a reusable remote-signer session and sends encrypted signer requests through signer relays. Forget removes only this device's saved remote-signer session.",
        )
        SignInInfoTopic.ExistingNsec -> SignInInfoCopy(
            title = "Existing nsec",
            body = "This uses the pasted nsec only for the current session. Other Note does not save it to app files. On desktop, saving to the keyring is a separate explicit action. Keep your nsec somewhere secure.",
        )
        SignInInfoTopic.CreateIdentity -> SignInInfoCopy(
            title = "Create identity",
            body = "This creates a fresh nsec. Other Note cannot recover it for you. Save it securely or import it into a signer before relying on this identity long term.",
        )
        SignInInfoTopic.LocalOnly -> SignInInfoCopy(
            title = "Local-only",
            body = "Local-only mode does not sync to relays and does not use a signer. It is useful for quick local notes, but it is not an encrypted Nostr account session.",
        )
        SignInInfoTopic.DesktopKeyring -> SignInInfoCopy(
            title = "Desktop keyring",
            body = KeyringSaveWarningCopy.description,
        )
    }

fun buildSignInOptions(
    platform: AppPlatform,
    externalSignerAvailable: Boolean,
    remoteSignerAvailable: Boolean,
): List<SignInOptionUi> = buildList {
    if (externalSignerAvailable) {
        add(
            SignInOptionUi(
                kind = SignInOptionKind.AndroidSigner,
                label = "Use Android signer",
                supportingCopy = "Recommended on Android. Use Amber or another signer so Other Note never stores your nsec.",
                emphasis = SignInOptionEmphasis.Primary,
            ),
        )
    }
    if (remoteSignerAvailable) {
        add(
            SignInOptionUi(
                kind = SignInOptionKind.RemoteSigner,
                label = "Connect bunker",
                supportingCopy = "Advanced. Pair with a bunker or remote signer while your private key stays in that signer.",
                emphasis = SignInOptionEmphasis.Secondary,
            ),
        )
    }
    add(
        SignInOptionUi(
            kind = SignInOptionKind.ExistingNsec,
            label = "Use nsec for this session",
            supportingCopy = "Session-only. Paste an existing nsec without saving it to this device.",
            emphasis = SignInOptionEmphasis.Low,
        ),
    )
    add(
        SignInOptionUi(
            kind = SignInOptionKind.CreateIdentity,
            label = "Create new identity",
            supportingCopy = "Generate a fresh nsec only if you are ready to save it somewhere secure.",
            emphasis = SignInOptionEmphasis.Text,
        ),
    )
}
