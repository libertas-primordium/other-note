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

fun buildSignInOptions(
    platform: AppPlatform,
    externalSignerAvailable: Boolean,
    remoteSignerAvailable: Boolean,
): List<SignInOptionUi> = buildList {
    if (externalSignerAvailable) {
        add(
            SignInOptionUi(
                kind = SignInOptionKind.AndroidSigner,
                label = "Continue with Android signer",
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
                supportingCopy = "Advanced. Connect a NIP-46 bunker or remote signer.",
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
