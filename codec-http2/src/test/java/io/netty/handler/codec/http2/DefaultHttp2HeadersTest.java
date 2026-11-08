/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.codec.http2;

import io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName;
import io.netty.util.AsciiString;
import io.netty.util.internal.StringUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Map.Entry;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.util.AsciiString.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class DefaultHttp2HeadersTest {

    @Test
    public void nullHeaderNameNotAllowed() {
        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new DefaultHttp2Headers().add(null, "foo");
            }
        });
    }

    @Test
    public void emptyHeaderNameNotAllowed() {
        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new DefaultHttp2Headers().add(StringUtil.EMPTY_STRING, "foo");
            }
        });
    }

    @Test
    public void testPseudoHeadersMustComeFirstWhenIterating() {
        Http2Headers headers = newHeaders();

        verifyPseudoHeadersFirst(headers);
        verifyAllPseudoHeadersPresent(headers);
    }

    @Test
    public void testPseudoHeadersWithRemovePreservesPseudoIterationOrder() {
        Http2Headers headers = newHeaders();

        Http2Headers nonPseudoHeaders = new DefaultHttp2Headers();
        for (Entry<CharSequence, CharSequence> entry : headers) {
            if (entry.getKey().length() == 0 || entry.getKey().charAt(0) != ':' &&
                !nonPseudoHeaders.contains(entry.getKey())) {
                nonPseudoHeaders.add(entry.getKey(), entry.getValue());
            }
        }

        assertFalse(nonPseudoHeaders.isEmpty());

        // Remove all the non-pseudo headers and verify
        for (Entry<CharSequence, CharSequence> nonPseudoHeaderEntry : nonPseudoHeaders) {
            assertTrue(headers.remove(nonPseudoHeaderEntry.getKey()));
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }

        // Add back all non-pseudo headers
        for (Entry<CharSequence, CharSequence> nonPseudoHeaderEntry : nonPseudoHeaders) {
            headers.add(nonPseudoHeaderEntry.getKey(), of("goo"));
            verifyPseudoHeadersFirst(headers);
            verifyAllPseudoHeadersPresent(headers);
        }
    }

    @Test
    public void testPseudoHeadersWithClearDoesNotLeak() {
        Http2Headers headers = newHeaders();

        assertFalse(headers.isEmpty());
        headers.clear();
        assertTrue(headers.isEmpty());

        // Combine 2 headers together, make sure pseudo headers stay up front.
        headers.add("name1", "value1").scheme("nothing");
        verifyPseudoHeadersFirst(headers);

        Http2Headers other = new DefaultHttp2Headers().add("name2", "value2").authority("foo");
        verifyPseudoHeadersFirst(other);

        headers.add(other);
        verifyPseudoHeadersFirst(headers);

        // Make sure the headers are what we expect them to be, and no leaking behind the scenes.
        assertEquals(4, headers.size());
        assertEquals("value1", headers.get("name1"));
        assertEquals("value2", headers.get("name2"));
        assertEquals("nothing", headers.scheme());
        assertEquals("foo", headers.authority());
    }

    @Test
    public void testSetHeadersOrdersPseudoHeadersCorrectly() {
        Http2Headers headers = newHeaders();
        Http2Headers other = new DefaultHttp2Headers().add("name2", "value2").authority("foo");

        headers.set(other);
        verifyPseudoHeadersFirst(headers);
        assertEquals(other.size(), headers.size());
        assertEquals("foo", headers.authority());
        assertEquals("value2", headers.get("name2"));
    }

    @Test
    public void testSetAllOrdersPseudoHeadersCorrectly() {
        Http2Headers headers = newHeaders();
        Http2Headers other = new DefaultHttp2Headers().add("name2", "value2").authority("foo");

        int headersSizeBefore = headers.size();
        headers.setAll(other);
        verifyPseudoHeadersFirst(headers);
        verifyAllPseudoHeadersPresent(headers);
        assertEquals(headersSizeBefore + 1, headers.size());
        assertEquals("foo", headers.authority());
        assertEquals("value2", headers.get("name2"));
    }

    @Test
    public void testHeaderNameValidation() {
        final Http2Headers headers = newHeaders();

        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                headers.add(of("Foo"), of("foo"));
            }
        });
    }

    @Test
    public void testClearResetsPseudoHeaderDivision() {
        DefaultHttp2Headers http2Headers = new DefaultHttp2Headers();
        http2Headers.method("POST");
        http2Headers.set("some", "value");
        http2Headers.clear();
        http2Headers.method("GET");
        assertEquals(1, http2Headers.names().size());
    }

    @Test
    public void testContainsNameAndValue() {
        Http2Headers headers = newHeaders();
        assertTrue(headers.contains("name1", "value2"));
        assertFalse(headers.contains("name1", "Value2"));
        assertTrue(headers.contains("2name", "Value3", true));
        assertFalse(headers.contains("2name", "Value3", false));
    }

    @Test
    public void testContainsName() {
        Http2Headers headers = new DefaultHttp2Headers();
        headers.add(CONTENT_LENGTH, "36");
        assertFalse(headers.contains("Content-Length"));
        assertTrue(headers.contains("content-length"));
        assertTrue(headers.contains(CONTENT_LENGTH));
        headers.remove(CONTENT_LENGTH);
        assertFalse(headers.contains("Content-Length"));
        assertFalse(headers.contains("content-length"));
        assertFalse(headers.contains(CONTENT_LENGTH));

        assertFalse(headers.contains("non-existent-name"));
        assertFalse(headers.contains(new AsciiString("non-existent-name")));
    }

    @Test
    void setMustOverwritePseudoHeaders() {
        Http2Headers headers = newHeaders();
        // The headers are already populated with pseudo headers.
        headers.method(of("GET"));
        headers.path(of("/index2.html"));
        headers.status(of("101"));
        headers.authority(of("github.com"));
        headers.scheme(of("http"));
        headers.set(of(":protocol"), of("http"));
        assertEquals(of("GET"), headers.method());
        assertEquals(of("/index2.html"), headers.path());
        assertEquals(of("101"), headers.status());
        assertEquals(of("github.com"), headers.authority());
        assertEquals(of("http"), headers.scheme());
    }

    @ParameterizedTest(name = "{displayName} [{index}] name={0} value={1}")
    @CsvSource(value = {"upgrade,protocol1", "connection,close", "keep-alive,timeout=5", "proxy-connection,close",
            "transfer-encoding,chunked", "te,something-else"})
    void possibleToAddConnectionHeaders(String name, String value) {
        Http2Headers headers = newHeaders();
        headers.add(name, value);
        assertTrue(headers.contains(name, value));
    }

    @Test
    public void headerValueValidationIsEnabledByDefault() {
        for (char illegalChar : new char[] {'\0', '\n', '\r'}) {
            String value = "fo" + illegalChar + "o";
            assertValueRejected(new DefaultHttp2Headers(), value);
            assertValueRejected(new DefaultHttp2Headers(true), value);
            assertValueRejected(new DefaultHttp2Headers(true, 16), value);
            assertValueRejected(new DefaultHttp2Headers(true, true, 16), value);
        }
        // A value that would split the header block once the headers are translated to HTTP/1.x.
        assertValueRejected(new DefaultHttp2Headers(), "a\r\nTransfer-Encoding: chunked");
        assertValueRejected(new DefaultHttp2Headers(), new AsciiString("a\r\nTransfer-Encoding: chunked"));
    }

    @Test
    public void headerValueValidationCharacterSet() {
        assertValue("", false);
        for (char c = 0; c <= 0xFF; c++) {
            // The first character must be a field-vchar (VCHAR / obs-text), later ones may also be SP / HTAB.
            boolean firstIllegal = c < 0x21 || c == 0x7F;
            boolean laterIllegal = c < 0x20 && c != 0x09 || c == 0x7F;
            assertValue(c + "a", firstIllegal);
            assertValue("a" + c, laterIllegal);
            assertValue("a" + c + 'a', laterIllegal);
        }
        // Characters above 0xFF are not restricted for CharSequence values.
        assertValue(new String(new char[] {'a', (char) 0x100, (char) 0xFFFF}), false);
    }

    @Test
    public void headerValueValidationAppliesToAllMutators() {
        Http2Headers headers = new DefaultHttp2Headers();
        headers.add("name", "value");
        try {
            headers.add("name", Arrays.<CharSequence>asList("ok", "b\rad"));
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.add("name", "ok", "b\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.addObject("name", "b\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.set("name", "b\0ad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.set("other", Arrays.<CharSequence>asList("ok", "b\rad"));
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.set("other", "ok", "b\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.setObject("other", "b\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.setObject("other", "ok", "b\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.path("/b\r\nad");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            headers.status("200\r\nSet-Cookie: injected");
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertNoIllegalValues(headers);

        // Copying headers whose values were never validated must not bypass the validation of the target, neither
        // when the names were not validated either nor when only the values were not validated.
        Http2Headers[] sources = {
                new DefaultHttp2Headers(false).add("name", "b\nad"),
                new DefaultHttp2Headers(true, false, 16).add("name", "b\nad")
        };
        for (Http2Headers source : sources) {
            Http2Headers target = new DefaultHttp2Headers();
            try {
                target.add(source);
                fail();
            } catch (IllegalArgumentException expected) {
                // expected
            }
            assertNoIllegalValues(target);
            target = new DefaultHttp2Headers();
            try {
                target.set(source);
                fail();
            } catch (IllegalArgumentException expected) {
                // expected
            }
            assertNoIllegalValues(target);
            target = new DefaultHttp2Headers();
            try {
                target.setAll(source);
                fail();
            } catch (IllegalArgumentException expected) {
                // expected
            }
            assertNoIllegalValues(target);
        }

        // Copying already validated headers keeps working.
        Http2Headers target = new DefaultHttp2Headers();
        target.add(new DefaultHttp2Headers().add("name", "value"));
        assertEquals("value", target.get("name"));
    }

    @Test
    public void headerValueValidationCanBeDisabled() {
        Http2Headers headers = new DefaultHttp2Headers(false);
        headers.add("name", "fo\r\n\0o");
        assertEquals("fo\r\n\0o", headers.get("name").toString());
        headers = new DefaultHttp2Headers(false, 16);
        headers.add("name", " fo\no");
        assertEquals(" fo\no", headers.get("name").toString());
        // Value validation can still be controlled separately from name validation.
        headers = new DefaultHttp2Headers(true, false, 16);
        headers.add("name", "fo\no");
        assertEquals("fo\no", headers.get("name").toString());
        assertValueRejected(new DefaultHttp2Headers(false, true, 16), "fo\no");
    }

    private static void verifyAllPseudoHeadersPresent(Http2Headers headers) {
        for (PseudoHeaderName pseudoName : PseudoHeaderName.values()) {
            assertNotNull(headers.get(pseudoName.value()));
        }
    }

    static void verifyPseudoHeadersFirst(Http2Headers headers) {
        CharSequence lastNonPseudoName = null;
        for (Entry<CharSequence, CharSequence> entry: headers) {
            if (entry.getKey().length() == 0 || entry.getKey().charAt(0) != ':') {
                lastNonPseudoName = entry.getKey();
            } else if (lastNonPseudoName != null) {
                fail("All pseudo headers must be first in iteration. Pseudo header " + entry.getKey() +
                        " is after a non pseudo header " + lastNonPseudoName);
            }
        }
    }

    private static void assertValue(String value, boolean illegal) {
        assertValue(value, illegal, value);
        assertValue(new AsciiString(value), illegal, value);
    }

    private static void assertValue(CharSequence value, boolean illegal, String text) {
        boolean rejected;
        try {
            new DefaultHttp2Headers().add("name", value);
            rejected = false;
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assertEquals(illegal, rejected, (illegal ? "value accepted: " : "value rejected: ") + hex(text));
    }

    private static void assertValueRejected(Http2Headers headers, CharSequence value) {
        try {
            headers.add("name", value);
            fail("value accepted: " + hex(value));
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertFalse(headers.contains("name"));
    }

    private static void assertNoIllegalValues(Http2Headers headers) {
        for (Entry<CharSequence, CharSequence> entry : headers) {
            String value = entry.getValue().toString();
            assertTrue(value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\0') < 0,
                    "illegal value stored for " + entry.getKey() + ": " + hex(value));
        }
    }

    private static String hex(CharSequence value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            sb.append(" 0x").append(Integer.toHexString(value.charAt(i)));
        }
        return sb.toString();
    }

    private static Http2Headers newHeaders() {
        Http2Headers headers = new DefaultHttp2Headers();
        headers.add(of("name1"), of("value1"), of("value2"));
        headers.method(of("POST"));
        headers.add(of("2name"), of("value3"));
        headers.path(of("/index.html"));
        headers.status(of("200"));
        headers.authority(of("netty.io"));
        headers.add(of("name3"), of("value4"));
        headers.scheme(of("https"));
        headers.add(of(":protocol"), of("websocket"));
        return headers;
    }
}
