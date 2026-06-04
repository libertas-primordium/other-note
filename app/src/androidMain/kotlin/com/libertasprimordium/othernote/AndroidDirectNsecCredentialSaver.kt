package com.libertasprimordium.othernote

import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import com.libertasprimordium.othernote.ui.DirectNsecCredentialSaveResult
import com.libertasprimordium.othernote.ui.DirectNsecCredentialSaver

class AndroidDirectNsecCredentialSaver(
    private val context: Context,
) : DirectNsecCredentialSaver {
    private val credentialManager: CredentialManager = CredentialManager.create(context)

    override suspend fun saveDirectNsecCredential(
        accountIdentifier: String,
        nsec: String,
    ): DirectNsecCredentialSaveResult =
        try {
            credentialManager.createCredential(
                context = context,
                request = CreatePasswordRequest(
                    id = accountIdentifier,
                    password = nsec,
                    preferImmediatelyAvailableCredentials = false,
                    isAutoSelectAllowed = false,
                ),
            )
            DirectNsecCredentialSaveResult.Saved
        } catch (_: CreateCredentialCancellationException) {
            DirectNsecCredentialSaveResult.Canceled
        } catch (_: CreateCredentialProviderConfigurationException) {
            DirectNsecCredentialSaveResult.Unavailable
        } catch (_: CreateCredentialUnsupportedException) {
            DirectNsecCredentialSaveResult.Unavailable
        } catch (_: CreateCredentialInterruptedException) {
            DirectNsecCredentialSaveResult.Failed("Password-manager save was interrupted. You are still signed in for this session.")
        } catch (_: CreateCredentialException) {
            DirectNsecCredentialSaveResult.Failed("Password-manager save failed. You are still signed in for this session.")
        } catch (_: IllegalArgumentException) {
            DirectNsecCredentialSaveResult.Failed("Password-manager save could not use this credential. You are still signed in for this session.")
        } catch (_: SecurityException) {
            DirectNsecCredentialSaveResult.Unavailable
        }
}
