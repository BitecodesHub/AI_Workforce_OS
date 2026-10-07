package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ConversationParticipants;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Taking a file in and deciding who may read it: type by content, the size limit, the private
 * thread rule, drafts that are the uploader's alone, and the checks a message's files pass on send.
 */
class AttachmentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private ChatAttachments repo;
    private AttachmentReader reader;
    private Conversations conversations;
    private ConversationParticipants participants;
    private AttachmentService service;
    private Conversation privateThread;
    private final List<ChatAttachments.NewAttachment> stored = new ArrayList<>();

    private static Actor user(UUID id) {
        return Actor.user(id.toString(), ORG.toString(), "role", Set.of("chat:use"), 0L);
    }

    @BeforeEach
    void setUp() {
        repo = mock(ChatAttachments.class);
        reader = mock(AttachmentReader.class);
        conversations = mock(Conversations.class);
        participants = mock(ConversationParticipants.class);
        ConversationAccess access =
                new ConversationAccess(conversations, participants, mock(JdbcTemplate.class), mock(AuditClient.class));
        PlatformProperties properties = mock(PlatformProperties.class);
        PlatformProperties.Services services = mock(PlatformProperties.Services.class);
        when(properties.services()).thenReturn(services);
        when(services.knowledge()).thenReturn("http://localhost:1");
        service = new AttachmentService(repo, reader, access, conversations, WebClient.builder(), properties);

        privateThread = new Conversation();
        privateThread.setId(UUID.randomUUID());
        privateThread.setOrgId(ORG);
        org.springframework.test.util.ReflectionTestUtils.setField(privateThread, "createdBy", ALICE.toString());
        privateThread.setVisibility("private");
        when(conversations.findByIdAndOrgId(privateThread.getId(), ORG)).thenReturn(Optional.of(privateThread));

        // What is inserted is what is read back.
        org.mockito.Mockito.doAnswer(call -> {
                    stored.add(call.getArgument(0));
                    return null;
                })
                .when(repo)
                .insert(any());
        when(repo.find(eq(ORG), any())).thenAnswer(call -> stored.stream()
                .filter(a -> a.id().equals(call.getArgument(1)))
                .findFirst()
                .map(a -> row(a.id(), a.conversationId(), null, a.uploadedBy(), a.name(), a.mediaType(), a.kind(), a.status())));
    }

    private static ChatAttachments.Row row(
            UUID id, UUID conversationId, UUID messageId, String uploadedBy, String name, String type, String kind, String status) {
        return new ChatAttachments.Row(
                id, ORG, conversationId, messageId, null, uploadedBy, name, type, kind, 10, "h", status, null, null, null,
                null, Instant.now(), null);
    }

    private void reads(String mediaType, String text, String problem) {
        when(reader.read(eq(ORG), any(), any()))
                .thenReturn(new AttachmentReader.Read(mediaType, text, 1, "hash", problem, null, false));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---- Upload --------------------------------------------------------------------------------

    @Test
    @DisplayName("a readable PDF is stored with its text, for its conversation, by the person who sent it")
    void storesReadableFile() {
        reads("application/pdf", "Revenue rose eleven percent.", null);

        AttachmentService.AttachmentView view =
                service.upload(ORG, user(ALICE), privateThread.getId(), "../../q3.pdf", bytes("%PDF-1.7 ..."));

        assertThat(view.name()).isEqualTo("q3.pdf");
        assertThat(view.kind()).isEqualTo("pdf");
        assertThat(view.status()).isEqualTo("ready");
        ChatAttachments.NewAttachment saved = stored.getFirst();
        assertThat(saved.orgId()).isEqualTo(ORG);
        assertThat(saved.conversationId()).isEqualTo(privateThread.getId());
        assertThat(saved.uploadedBy()).isEqualTo(ALICE.toString());
        assertThat(saved.text()).isEqualTo("Revenue rose eleven percent.");
    }

    @Test
    @DisplayName("the type comes from the content: a program named invoice.pdf is refused and nothing is stored")
    void refusesByContentType() {
        reads("application/x-msdownload", "", "The file could not be read.");

        assertThatThrownBy(() -> service.upload(ORG, user(ALICE), null, "invoice.pdf", bytes("MZ....")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getMessage()).startsWith("invoice.pdf is not a file type Chat can read.");
                });
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("a file over 25 MB is refused before it is read; an empty one is refused too")
    void sizeLimit() {
        byte[] big = new byte[(int) AttachmentTypes.MAX_BYTES + 1];
        assertThatThrownBy(() -> service.upload(ORG, user(ALICE), null, "big.pdf", big))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("big.pdf is larger than 25 MB.");
                });
        assertThatThrownBy(() -> service.upload(ORG, user(ALICE), null, "empty.txt", new byte[0]))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reader, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("the anti-virus test file is refused")
    void refusesTestVirus() {
        byte[] eicar = bytes("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");
        assertThatThrownBy(() -> service.upload(ORG, user(ALICE), null, "eicar.txt", eicar))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("virus test signature");
    }

    @Test
    @DisplayName("a scan with no text is kept as unreadable with a plain reason; a picture is ready")
    void unreadableAndImages() {
        reads("application/pdf", "", "The PDF has no text layer. It is most likely a scan, and needs optical "
                + "character recognition before it can be indexed.");
        AttachmentService.AttachmentView scan = service.upload(ORG, user(ALICE), null, "scan.pdf", bytes("%PDF"));
        assertThat(scan.status()).isEqualTo("unreadable");
        assertThat(stored.getLast().problem()).doesNotContain("indexed");

        reads("image/png", "", "The file is an image with no text layer.");
        AttachmentService.AttachmentView png = service.upload(ORG, user(ALICE), null, "chart.png", bytes("PNG"));
        assertThat(png.status()).isEqualTo("ready");
        assertThat(png.kind()).isEqualTo("image");
        assertThat(png.imageReadable()).isTrue();

        reads("image/heic", "", "The file is an image with no text layer.");
        assertThat(service.upload(ORG, user(ALICE), null, "photo.heic", bytes("HEIC")).imageReadable()).isFalse();
    }

    @Test
    @DisplayName("nobody outside a private conversation can attach to it; it is reported as not found")
    void privateConversationUpload() {
        reads("text/plain", "hello", null);
        assertThatThrownBy(() -> service.upload(ORG, user(BOB), privateThread.getId(), "notes.txt", bytes("hello")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---- Reading -------------------------------------------------------------------------------

    @Test
    @DisplayName("a sent file in a private thread is read by its members and hidden from everyone else")
    void sentFileFollowsConversation() {
        UUID id = UUID.randomUUID();
        when(repo.find(ORG, id)).thenReturn(Optional.of(
                row(id, privateThread.getId(), UUID.randomUUID(), ALICE.toString(), "q3.pdf", "application/pdf", "pdf", "ready")));
        when(repo.content(ORG, id)).thenReturn(Optional.of(bytes("%PDF")));

        assertThat(service.download(ORG, user(ALICE), id).content()).isNotEmpty();
        assertThatThrownBy(() -> service.download(ORG, user(BOB), id))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);

        when(participants.existsByConversationIdAndUserId(privateThread.getId(), BOB.toString())).thenReturn(true);
        assertThat(service.view(ORG, user(BOB), id).name()).isEqualTo("q3.pdf");
    }

    @Test
    @DisplayName("an unsent file is the uploader's alone, and a file from another workspace is never found")
    void draftsAndWorkspaces() {
        UUID id = UUID.randomUUID();
        when(repo.find(ORG, id)).thenReturn(Optional.of(
                row(id, null, null, ALICE.toString(), "draft.txt", "text/plain", "text", "ready")));
        assertThat(service.view(ORG, user(ALICE), id).name()).isEqualTo("draft.txt");
        assertThatThrownBy(() -> service.view(ORG, user(BOB), id)).isInstanceOf(ApiException.class);

        UUID otherOrg = UUID.randomUUID();
        when(repo.find(otherOrg, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.view(otherOrg, Actor.user(ALICE.toString(), otherOrg.toString(), "r", Set.of(), 0L), id))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("only the uploader takes back an unsent file; a sent one stays with its conversation")
    void deleteRules() {
        UUID draft = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        when(repo.find(ORG, draft)).thenReturn(Optional.of(row(draft, null, null, ALICE.toString(), "a.txt", "text/plain", "text", "ready")));
        when(repo.find(ORG, sent)).thenReturn(Optional.of(
                row(sent, privateThread.getId(), UUID.randomUUID(), ALICE.toString(), "b.txt", "text/plain", "text", "ready")));

        assertThatThrownBy(() -> service.delete(ORG, user(BOB), draft)).isInstanceOf(ApiException.class);
        service.delete(ORG, user(ALICE), draft);
        verify(repo).delete(ORG, draft);
        assertThatThrownBy(() -> service.delete(ORG, user(ALICE), sent))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---- Sending -------------------------------------------------------------------------------

    @Test
    @DisplayName("a message carries only its sender's unsent, readable files, at most ten")
    void checkForSend() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID unreadable = UUID.randomUUID();
        ChatAttachments.Row mineRow = row(mine, null, null, ALICE.toString(), "a.pdf", "application/pdf", "pdf", "ready");
        when(repo.findAll(ORG, List.of(mine))).thenReturn(List.of(mineRow));
        when(repo.findAll(ORG, List.of(theirs)))
                .thenReturn(List.of(row(theirs, null, null, BOB.toString(), "b.pdf", "application/pdf", "pdf", "ready")));
        when(repo.findAll(ORG, List.of(unreadable)))
                .thenReturn(List.of(row(unreadable, null, null, ALICE.toString(), "scan.pdf", "application/pdf", "pdf", "unreadable")));

        assertThat(service.checkForSend(ORG, user(ALICE), privateThread.getId(), List.of(mine))).containsExactly(mineRow);
        assertThatThrownBy(() -> service.checkForSend(ORG, user(ALICE), privateThread.getId(), List.of(theirs)))
                .hasMessageContaining("no longer available");
        assertThatThrownBy(() -> service.checkForSend(ORG, user(ALICE), privateThread.getId(), List.of(unreadable)))
                .hasMessageContaining("scan.pdf could not be read");
        List<UUID> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(UUID.randomUUID());
        }
        assertThatThrownBy(() -> service.checkForSend(ORG, user(ALICE), privateThread.getId(), eleven))
                .hasMessageContaining("at most 10 files");
        verify(repo, never()).findAll(eq(ORG), eq(eleven));
    }

    @Test
    @DisplayName("the coordinator reads the files' names; a message with only files asks plainly about them")
    void requestWithNames() {
        ChatAttachments.Row pdf = row(UUID.randomUUID(), null, null, "a", "q3.pdf", "application/pdf", "pdf", "ready");
        assertThat(AttachmentService.requestWithNames("", List.of(pdf)))
                .isEqualTo("Look at the attached file and tell me what it contains.\n\nAttached: q3.pdf (PDF).");
        assertThat(AttachmentService.requestWithNames("Summarise this", List.of(pdf, pdf)))
                .startsWith("Summarise this\n\nAttached: q3.pdf (PDF), q3.pdf (PDF).");
        assertThat(AttachmentService.requestWithNames("Hi", List.of())).isEqualTo("Hi");
    }

    @Test
    @DisplayName("unsent drafts are swept after a day")
    void sweep() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        service.sweepUnsent();
        verify(repo).deleteUnsentBefore(cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), Instant.now())).isBetween(Duration.ofHours(23), Duration.ofHours(25));
        verify(repo, never()).bindToMessage(any(), any(), any(), anyList());
    }
}
