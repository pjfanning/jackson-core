package tools.jackson.core.json.async;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.CharBuffer;

import tools.jackson.core.*;
import tools.jackson.core.async.CharBufferFeeder;
import tools.jackson.core.async.NonBlockingInputFeeder;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.sym.ByteQuadsCanonicalizer;
import tools.jackson.core.sym.CharsToNameCanonicalizer;

/**
 * Non-blocking parser implementation for JSON content that takes its input
 * via {@link java.nio.CharBuffer} instances passed.
 *<p>
 * Supports full Unicode content since input is already decoded into Java chars.
 * Char offsets are tracked for location reporting.
 */
public class NonBlockingCharBufferJsonParser
    extends NonBlockingJsonCharParserBase
    implements CharBufferFeeder
{
    private static final char[] NO_CHARS = new char[0];

    // Internal char[] buffer copied from the CharBuffer for simpler access
    private char[] _inputBuffer = NO_CHARS;

    public NonBlockingCharBufferJsonParser(ObjectReadContext readCtxt, IOContext ctxt,
            int stdFeatures, int formatReadFeatures,
            ByteQuadsCanonicalizer dummyByteSym, CharsToNameCanonicalizer charSym) {
        super(readCtxt, ctxt, stdFeatures, formatReadFeatures, dummyByteSym, charSym);
    }

    @Override
    public NonBlockingInputFeeder nonBlockingInputFeeder() {
        return this;
    }

    @Override
    public void feedInput(final CharBuffer buffer) throws JacksonException {
        if (_inputPtr < _inputEnd) {
            _reportError("Still have %d undecoded chars, should not call 'feedInput'", _inputEnd - _inputPtr);
        }
        final int start = buffer.position();
        final int end = buffer.limit();
        if (end < start) {
            _reportError("Input end (%d) may not be before start (%d)", end, start);
        }
        if (_endOfInput) {
            _reportError("Already closed, cannot feed more input");
        }
        _currInputProcessed += _origBufferLen;
        _streamReadConstraints.validateDocumentLength(_currInputProcessed);

        final int len = end - start;
        // Copy CharBuffer contents to a char[] for simpler random-access
        if (_inputBuffer.length < len) {
            _inputBuffer = new char[Math.max(len, _inputBuffer.length * 2)];
        }
        // Use duplicate to avoid mutating the buffer's position
        buffer.duplicate().get(_inputBuffer, 0, len);

        // Since we use 0-based indexing in copied buffer, adjust row start
        // The same logic as ByteArrayFeeder: account for how far into the old buffer we got
        _currInputRowStart = -(_inputEnd - _currInputRowStart);
        _currBufferStart = 0;
        _inputPtr = 0;
        _inputEnd = len;
        _origBufferLen = len;
    }

    @Override
    public int releaseBuffered(final OutputStream out) throws JacksonException {
        final int avail = _inputEnd - _inputPtr;
        if (avail > 0) {
            try {
                for (int i = _inputPtr; i < _inputEnd; i++) {
                    int c = _inputBuffer[i];
                    if (c < 0x80) {
                        out.write(c);
                    } else if (c < 0x800) {
                        out.write(0xC0 | (c >> 6));
                        out.write(0x80 | (c & 0x3F));
                    } else {
                        out.write(0xE0 | (c >> 12));
                        out.write(0x80 | ((c >> 6) & 0x3F));
                        out.write(0x80 | (c & 0x3F));
                    }
                }
            } catch (IOException e) {
                throw _wrapIOFailure(e);
            }
        }
        return avail;
    }

    @Override
    protected int getNextCharFromBuffer() {
        return _inputBuffer[_inputPtr++];
    }

    @Override
    protected int getCharFromBuffer(final int ptr) {
        return _inputBuffer[ptr];
    }

    @Override
    protected char[] _inputBuffer_forName() {
        return _inputBuffer;
    }
}
