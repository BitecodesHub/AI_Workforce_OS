// @find: tests for concurrent credential reads, reveal records use, last used, optimistic locking, no version conflict
// @what: Tests that revealing a credential records its use without a versioned save.
// @flow: Exercises CredentialService.reveal and Credentials.recordUse.
package os.aiworkforce.organisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.organisation.domain.Credential;
import os.aiworkforce.organisation.repository.Credentials;
import os.aiworkforce.organisation.service.CredentialService;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;

/**
 * Reading a key records the read with a plain update, never a versioned save: concurrent runs of
 * one workspace read the same key at once, and a versioned save failed one of them with a 409.
 */
class CredentialConcurrentReadTest {

    @Test
    @DisplayName("a reveal records the read without saving the entity, so concurrent reads never conflict")
    void revealRecordsUseWithoutAVersionedSave() {
        Credentials credentials = mock(Credentials.class);
        EnvelopeEncryptionService encryption = mock(EnvelopeEncryptionService.class);
        UUID org = UUID.randomUUID();
        Credential credential = mock(Credential.class);
        UUID id = UUID.randomUUID();
        when(credential.getId()).thenReturn(id);
        when(credential.hasExpired()).thenReturn(false);
        when(credential.getEncryptedValue()).thenReturn("cipher");
        when(credentials.findByOrgIdAndRef(org, "provider:nvidia")).thenReturn(Optional.of(credential));
        when(encryption.decrypt(anyString(), eq("cipher"))).thenReturn("nvapi-test");

        Optional<String> value = new CredentialService(credentials, encryption).reveal(org, "provider:nvidia");

        assertThat(value).contains("nvapi-test");
        verify(credentials).recordUse(eq(id), any());
        verify(credentials, never()).save(any());
    }
}
