/*
 * SPDX-FileCopyrightText: Copyright (c) 2014-2026 Yegor Bugayenko
 * SPDX-License-Identifier: MIT
 */
package com.jcabi.email;

import com.google.common.base.Joiner;
import com.jcabi.email.enclosure.EnBinary;
import com.jcabi.email.enclosure.EnHtml;
import com.jcabi.email.enclosure.EnPlain;
import com.jcabi.email.stamp.StRecipient;
import com.jcabi.email.stamp.StSender;
import com.jcabi.email.stamp.StSubject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Test case for {@link Envelope}.
 *
 * @since 1.4
 */
final class EnvelopeTest {

    /**
     * MIME subtype of a mixed container.
     */
    private static final String MIXED = "multipart/mixed";

    /**
     * MIME subtype of an alternative container.
     */
    private static final String ALT = "multipart/alternative";

    /**
     * Content type of a binary GIF attachment.
     */
    private static final String GIF = "image/gif";

    /**
     * File name of a GIF attachment.
     */
    private static final String PIC = "picture.gif";

    /**
     * File name of a text attachment.
     */
    private static final String NOTES = "notes.txt";

    /**
     * Content of the plain body part.
     */
    private static final String PLAIN = "plain alternative body line";

    /**
     * Content of the HTML body part.
     */
    private static final String HTML = "html alternative body line";

    /**
     * Payload stored inside the text attachment.
     */
    private static final String TEXT = "attached notes payload line";

    /**
     * Prefix of a temporary file.
     */
    private static final String PREFIX = "jcabi-email";

    /**
     * Suffix of a temporary GIF file.
     */
    private static final String SUFFIX_GIF = ".gif";

    /**
     * Envelope.Constant can cache.
     *
     * @throws Exception If fails
     */
    @Test
    void cachesPreviousCall() throws Exception {
        final Envelope origin = Mockito.mock(Envelope.class);
        Mockito.doReturn(
            new MimeMessage(Session.getDefaultInstance(new Properties()))
        ).when(origin).unwrap();
        final Envelope envelope = new Envelope.Constant(origin);
        envelope.unwrap();
        envelope.unwrap();
        Mockito.verify(origin, Mockito.times(1)).unwrap();
    }

    /**
     * Envelope.Constant keeps different recipients in separate cache entries.
     *
     * @throws Exception If fails
     */
    @Test
    void cachesRecipientsSeparately() throws Exception {
        final Envelope.Mime body = new Envelope.Mime()
            .with(new EnPlain("Your order has shipped"));
        new Envelope.Constant(
            body.with(new StRecipient("alice@example.com"))
        ).unwrap();
        MatcherAssert.assertThat(
            new Envelope.Constant(
                body.with(new StRecipient("bob@example.com"))
            ).unwrap().getAllRecipients(),
            Matchers.arrayContaining(new InternetAddress("bob@example.com"))
        );
    }

    /**
     * Envelope should handle non-Latin characters.
     *
     * @throws Exception Thrown in case of problem of writing a message to
     *  string.
     */
    @Test
    void handlesUnicodeCorrectly() throws Exception {
        final Envelope env = new Envelope.Mime()
            .with(new StSender("from <test-from@jcabi.com>"))
            .with(new StRecipient("to", "test-to@jcabi.com"))
            .with(new StSubject("test subject: test me"))
            .with(new EnHtml("<html><body>привет</body></html>"));
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        env.unwrap().writeTo(stream);
        MatcherAssert.assertThat(
            stream.toString(StandardCharsets.UTF_8),
            Matchers.allOf(
                Matchers.containsString(
                    "Content-Type: text/html;charset=\"UTF-8\""
                ),
                Matchers.containsString(
                    "Content-Transfer-Encoding: quoted-printable"
                ),
                Matchers.containsString(
                    Joiner.on("").join(
                        "<html><body>",
                        "=D0=BF=D1=80=D0=B8=D0=B2=D0=B5=D1=82</body></html>"
                    )
                )
            )
        );
    }

    /**
     * Envelope.MIME can wrap another envelope.
     *
     * @throws Exception If fails
     */
    @Test
    @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
    void wrapsAnotherEnvelope() throws Exception {
        final Message message = new Envelope.Mime(
            new Envelope.Mime().with(new StSender("jack@example.com"))
        ).with(new StRecipient("paul@example.com")).unwrap();
        MatcherAssert.assertThat(
            message.getAllRecipients().length,
            Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            message.getFrom().length,
            Matchers.equalTo(1)
        );
    }

    /**
     * Envelope.Mime puts an attachment into a multipart/mixed container,
     * next to the body, instead of a multipart/alternative one.
     *
     * @throws Exception If fails
     */
    @Test
    void putsAttachmentsIntoMixedContainer() throws Exception {
        final File file = File.createTempFile(
            EnvelopeTest.PREFIX, EnvelopeTest.SUFFIX_GIF
        );
        try {
            Files.write(file.toPath(), new byte[] {1, 2, 3});
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            new Envelope.Mime()
                .with(new EnPlain("Here is the picture attached"))
                .with(new EnBinary(file, "my-picture.gif", EnvelopeTest.GIF))
                .unwrap()
                .writeTo(stream);
            MatcherAssert.assertThat(
                stream.toString(StandardCharsets.UTF_8),
                Matchers.allOf(
                    Matchers.containsString(EnvelopeTest.MIXED),
                    Matchers.not(
                        Matchers.containsString(EnvelopeTest.ALT)
                    ),
                    Matchers.containsString("filename=my-picture.gif")
                )
            );
        } finally {
            Files.delete(file.toPath());
        }
    }

    /**
     * Envelope.Mime groups both bodies into one nested alternative part and
     * keeps the attachment as its sibling inside the mixed container.
     *
     * @throws Exception If fails
     */
    @Test
    void nestsBodiesWithAttachmentAsSibling() throws Exception {
        final File file = File.createTempFile(
            EnvelopeTest.PREFIX, EnvelopeTest.SUFFIX_GIF
        );
        try {
            Files.write(file.toPath(), new byte[] {1, 2, 3});
            final MimeMultipart mixed = (MimeMultipart) new Envelope.Mime()
                .with(new EnPlain(EnvelopeTest.PLAIN))
                .with(new EnHtml(EnvelopeTest.HTML))
                .with(new EnBinary(file, EnvelopeTest.PIC, EnvelopeTest.GIF))
                .unwrap().getContent();
            MatcherAssert.assertThat(
                Arrays.asList(
                    mixed.getContentType(),
                    MimeMultipart.class.cast(
                        mixed.getBodyPart(0).getContent()
                    ).getContentType(),
                    mixed.getBodyPart(1).getFileName()
                ),
                Matchers.contains(
                    Matchers.startsWith(EnvelopeTest.MIXED),
                    Matchers.startsWith(EnvelopeTest.ALT),
                    Matchers.equalTo(EnvelopeTest.PIC)
                )
            );
        } finally {
            Files.delete(file.toPath());
        }
    }

    /**
     * Envelope.Mime preserves both the plain and the HTML body payloads
     * inside the nested alternative part.
     *
     * @throws Exception If fails
     */
    @Test
    void preservesBothBodiesInsideAlternative() throws Exception {
        final File file = File.createTempFile(
            EnvelopeTest.PREFIX, EnvelopeTest.SUFFIX_GIF
        );
        try {
            Files.write(file.toPath(), new byte[] {1, 2, 3});
            final MimeMultipart mixed = (MimeMultipart) new Envelope.Mime()
                .with(new EnPlain(EnvelopeTest.PLAIN))
                .with(new EnHtml(EnvelopeTest.HTML))
                .with(new EnBinary(file, EnvelopeTest.PIC, EnvelopeTest.GIF))
                .unwrap().getContent();
            final MimeMultipart alt = (MimeMultipart) mixed
                .getBodyPart(0).getContent();
            MatcherAssert.assertThat(
                Arrays.asList(
                    mixed.getContentType(),
                    alt.getContentType(),
                    (String) alt.getBodyPart(0).getContent(),
                    (String) alt.getBodyPart(1).getContent()
                ),
                Matchers.contains(
                    Matchers.startsWith(EnvelopeTest.MIXED),
                    Matchers.startsWith(EnvelopeTest.ALT),
                    Matchers.containsString(EnvelopeTest.PLAIN),
                    Matchers.containsString(EnvelopeTest.HTML)
                )
            );
        } finally {
            Files.delete(file.toPath());
        }
    }

    /**
     * Envelope.Mime keeps a text attachment outside the alternative group,
     * as a distinct attachment part that preserves its payload.
     *
     * @throws Exception If fails
     */
    @Test
    void keepsTextAttachmentOutsideAlternative() throws Exception {
        final File file = File.createTempFile(
            EnvelopeTest.PREFIX, ".txt"
        );
        try {
            Files.write(
                file.toPath(),
                EnvelopeTest.TEXT.getBytes(StandardCharsets.UTF_8)
            );
            final MimeMultipart mixed = (MimeMultipart) new Envelope.Mime()
                .with(new EnPlain(EnvelopeTest.PLAIN))
                .with(new EnHtml(EnvelopeTest.HTML))
                .with(new EnBinary(file, EnvelopeTest.NOTES, "text/plain"))
                .unwrap().getContent();
            MatcherAssert.assertThat(
                Arrays.asList(
                    MimeMultipart.class.cast(
                        mixed.getBodyPart(0).getContent()
                    ).getContentType(),
                    mixed.getBodyPart(1).getFileName(),
                    mixed.getBodyPart(1).getDisposition(),
                    new String(
                        mixed.getBodyPart(1).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                    )
                ),
                Matchers.contains(
                    Matchers.startsWith(EnvelopeTest.ALT),
                    Matchers.equalTo(EnvelopeTest.NOTES),
                    Matchers.equalToIgnoringCase(Part.ATTACHMENT),
                    Matchers.containsString(EnvelopeTest.TEXT)
                )
            );
        } finally {
            Files.delete(file.toPath());
        }
    }

    /**
     * Envelope.MIME can wrap another envelope with enclosures.
     *
     * @throws Exception If fails
     */
    @Test
    void wrapsAnotherEnvelopeWithEnclosures() throws Exception {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Multipart.class.cast(
            new Envelope.Mime(
                new Envelope.Mime().with(new EnPlain("first enclosure"))
            ).with(new EnPlain("second enclosure")).unwrap().getContent()
        ).writeTo(baos);
        MatcherAssert.assertThat(
            baos.toString(StandardCharsets.UTF_8),
            Matchers.allOf(
                Matchers.containsString("first"),
                Matchers.containsString("second")
            )
        );
    }
}
