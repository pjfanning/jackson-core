package tools.jackson.core.unittest.json.async;

import java.nio.CharBuffer;

import org.junit.jupiter.api.Test;

import tools.jackson.core.*;
import tools.jackson.core.async.CharArrayFeeder;
import tools.jackson.core.async.CharBufferFeeder;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.async.AsyncTestBase;
import tools.jackson.core.unittest.testutil.AsyncReaderWrapper;
import tools.jackson.core.unittest.testutil.AsyncReaderWrapperForCharArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for the non-blocking char-array and char-buffer JSON parsers.
 *
 * @see tools.jackson.core.json.async.NonBlockingCharArrayJsonParser
 * @see tools.jackson.core.json.async.NonBlockingCharBufferJsonParser
 */
class AsyncCharParserTest extends AsyncTestBase
{
    private final JsonFactory JSON_F = new JsonFactory();

    /*
    /**********************************************************************
    /* Factory method tests
    /**********************************************************************
     */

    @Test
    void createNonBlockingCharArrayParser() throws Exception {
        JsonParser p = JSON_F.createNonBlockingCharArrayParser(ObjectReadContext.empty());
        assertNotNull(p);
        assertInstanceOf(CharArrayFeeder.class, p.nonBlockingInputFeeder());
        p.close();
    }

    @Test
    void createNonBlockingCharBufferParser() throws Exception {
        JsonParser p = JSON_F.createNonBlockingCharBufferParser(ObjectReadContext.empty());
        assertNotNull(p);
        assertInstanceOf(CharBufferFeeder.class, p.nonBlockingInputFeeder());
        p.close();
    }

    /*
    /**********************************************************************
    /* Simple token tests: null, true, false
    /**********************************************************************
     */

    @Test
    void simpleTokens() throws Exception {
        final String doc = "[ true, false, null ]";
        final char[] chars = doc.toCharArray();

        // Test with various feed sizes
        for (int feedSize : new int[]{1, 2, 3, 5, 100}) {
            _testSimpleTokens(chars, feedSize);
        }
    }

    private void _testSimpleTokens(char[] chars, int feedSize) throws Exception {
        AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
        assertNull(r.currentToken());
        assertToken(JsonToken.START_ARRAY, r.nextToken());
        assertToken(JsonToken.VALUE_TRUE, r.nextToken());
        assertToken(JsonToken.VALUE_FALSE, r.nextToken());
        assertToken(JsonToken.VALUE_NULL, r.nextToken());
        assertToken(JsonToken.END_ARRAY, r.nextToken());
        assertNull(r.nextToken());
        assertTrue(r.isClosed());
    }

    /*
    /**********************************************************************
    /* String value tests
    /**********************************************************************
     */

    @Test
    void stringValues() throws Exception {
        final String doc = "[\"hello\",\"world\",\"\"]";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 3, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_ARRAY, r.nextToken());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("hello", r.currentText());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("world", r.currentText());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("", r.currentText());
            assertToken(JsonToken.END_ARRAY, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    @Test
    void stringWithUnicode() throws Exception {
        // Test with non-ASCII chars (these are already decoded in char input)
        final String doc = "[\"caf\u00e9\",\"\u4e2d\u6587\"]";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 5, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_ARRAY, r.nextToken());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("caf\u00e9", r.currentText());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("\u4e2d\u6587", r.currentText());
            assertToken(JsonToken.END_ARRAY, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    @Test
    void stringWithEscapes() throws Exception {
        final String doc = "[\"tab\\there\",\"new\\nline\",\"\\u0041\"]";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 5, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_ARRAY, r.nextToken());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("tab\there", r.currentText());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("new\nline", r.currentText());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("A", r.currentText());
            assertToken(JsonToken.END_ARRAY, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    /*
    /**********************************************************************
    /* Number tests
    /**********************************************************************
     */

    @Test
    void intValues() throws Exception {
        final String doc = "[0, 1, -1, 42, 1000000]";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 5, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_ARRAY, r.nextToken());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(0, r.getIntValue());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(1, r.getIntValue());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(-1, r.getIntValue());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(42, r.getIntValue());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(1000000, r.getIntValue());
            assertToken(JsonToken.END_ARRAY, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    @Test
    void floatValues() throws Exception {
        final String doc = "[3.14, -2.5, 1.0e10]";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 5, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_ARRAY, r.nextToken());
            assertToken(JsonToken.VALUE_NUMBER_FLOAT, r.nextToken());
            assertEquals(3.14, r.getDoubleValue(), 0.001);
            assertToken(JsonToken.VALUE_NUMBER_FLOAT, r.nextToken());
            assertEquals(-2.5, r.getDoubleValue(), 0.001);
            assertToken(JsonToken.VALUE_NUMBER_FLOAT, r.nextToken());
            assertEquals(1.0e10, r.getDoubleValue(), 1e5);
            assertToken(JsonToken.END_ARRAY, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    /*
    /**********************************************************************
    /* Object tests
    /**********************************************************************
     */

    @Test
    void simpleObject() throws Exception {
        final String doc = "{\"name\":\"Alice\",\"age\":30}";
        final char[] chars = doc.toCharArray();

        for (int feedSize : new int[]{1, 2, 5, 100}) {
            AsyncReaderWrapper r = _asyncForChars(feedSize, chars, 0);
            assertToken(JsonToken.START_OBJECT, r.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, r.nextToken());
            assertEquals("name", r.currentName());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals("Alice", r.currentText());
            assertToken(JsonToken.PROPERTY_NAME, r.nextToken());
            assertEquals("age", r.currentName());
            assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
            assertEquals(30, r.getIntValue());
            assertToken(JsonToken.END_OBJECT, r.nextToken());
            assertNull(r.nextToken());
        }
    }

    /*
    /**********************************************************************
    /* Location tests: char offset should be non-negative
    /**********************************************************************
     */

    @Test
    void charOffsetReportedNotByteOffset() throws Exception {
        // The key feature: char-based parser should report char offsets,
        // not byte offsets. Char offset should be >= 0; byte offset should be -1.
        JsonParser p = JSON_F.createNonBlockingCharArrayParser(ObjectReadContext.empty());
        CharArrayFeeder feeder = (CharArrayFeeder) p.nonBlockingInputFeeder();

        char[] input = "[[[".toCharArray();
        feeder.feedInput(input, 2, 3);
        assertEquals(JsonToken.START_ARRAY, p.nextToken());

        // Char offset should be tracked (not -1)
        assertEquals(1L, p.currentLocation().getCharOffset());
        // Byte offset should be -1 (not tracked for char input)
        assertEquals(-1L, p.currentLocation().getByteOffset());

        feeder.feedInput(input, 0, 1);
        assertEquals(JsonToken.START_ARRAY, p.nextToken());
        assertEquals(2L, p.currentLocation().getCharOffset());
        assertEquals(-1L, p.currentLocation().getByteOffset());

        p.close();
    }

    /*
    /**********************************************************************
    /* CharBuffer parser tests
    /**********************************************************************
     */

    @Test
    void charBufferSimpleDoc() throws Exception {
        final String doc = "{\"key\":\"value\"}";
        JsonParser p = JSON_F.createNonBlockingCharBufferParser(ObjectReadContext.empty());
        CharBufferFeeder feeder = (CharBufferFeeder) p.nonBlockingInputFeeder();

        // Feed the entire document at once
        feeder.feedInput(CharBuffer.wrap(doc.toCharArray()));
        feeder.endOfInput();

        assertToken(JsonToken.START_OBJECT, p.nextToken());
        assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
        assertEquals("key", p.currentName());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals("value", p.getString());
        assertToken(JsonToken.END_OBJECT, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    private AsyncReaderWrapper _asyncForChars(int charsPerFeed, char[] doc, int padding) {
        return new AsyncReaderWrapperForCharArray(
                JSON_F.createNonBlockingCharArrayParser(ObjectReadContext.empty()),
                charsPerFeed, doc, padding);
    }
}
