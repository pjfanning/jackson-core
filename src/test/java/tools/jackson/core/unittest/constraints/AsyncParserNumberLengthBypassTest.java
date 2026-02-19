package tools.jackson.core.unittest.constraints;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.core.*;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.async.NonBlockingByteArrayJsonParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Number Length Constraint Bypass in Non-Blocking (Async) JSON Parsers
 */
public class AsyncParserNumberLengthBypassTest {

    private static final int TEST_NUMBER_LENGTH = 5000;

    private final JsonFactory factory = new JsonFactory();

    @Test
    void asyncParserAcceptsLongNumber() throws Exception {
        byte[] payload = buildPayloadWithLongInteger(TEST_NUMBER_LENGTH);

        NonBlockingByteArrayJsonParser p =
                (NonBlockingByteArrayJsonParser) factory.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        p.feedInput(payload, 0, payload.length);
        p.endOfInput();

        boolean foundNumber = false;
        try {
            while (p.nextToken() != null) {
                if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                    foundNumber = true;
                    String numberText = p.getText();
                    assertEquals(TEST_NUMBER_LENGTH, numberText.length(),
                            "Async parser silently accepted all " + TEST_NUMBER_LENGTH + " digits");
                }
            }
            fail("Async parser must reject a " + TEST_NUMBER_LENGTH + "-digit number");
        } catch (StreamConstraintsException e) {
            assertTrue(e.getMessage().contains("Number value length"),
                    "Unexpected exception message: " + e.getMessage());
        }
        p.close();
    }

    @Test
    void asyncParserAcceptsLongDecimal() throws Exception {
        byte[] payload = buildPayloadWithLongDecimal(TEST_NUMBER_LENGTH);

        NonBlockingByteArrayJsonParser p =
                (NonBlockingByteArrayJsonParser) factory.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        p.feedInput(payload, 0, payload.length);
        p.endOfInput();

        boolean foundNumber = false;
        try {
            while (p.nextToken() != null) {
                if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                    foundNumber = true;
                    String numberText = p.getText();
                    assertEquals(TEST_NUMBER_LENGTH, numberText.length(),
                            "Async parser silently accepted all " + TEST_NUMBER_LENGTH + " digits");
                }
            }
            fail("Async parser must reject a " + TEST_NUMBER_LENGTH + "-digit number");
        } catch (StreamConstraintsException e) {
            assertTrue(e.getMessage().contains("Number value length"),
                    "Unexpected exception message: " + e.getMessage());
        }
        p.close();
    }

    private byte[] buildPayloadWithLongInteger(int numDigits) {
        StringBuilder sb = new StringBuilder(numDigits + 10);
        sb.append("{\"v\":");
        for (int i = 0; i < numDigits; i++) {
            sb.append((char) ('1' + (i % 9)));
        }
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildPayloadWithLongDecimal(int numDigits) {
        StringBuilder sb = new StringBuilder(numDigits + 10);
        sb.append("{\"v\":0.");
        for (int i = 0; i < numDigits; i++) {
            sb.append((char) ('1' + (i % 9)));
        }
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

}
