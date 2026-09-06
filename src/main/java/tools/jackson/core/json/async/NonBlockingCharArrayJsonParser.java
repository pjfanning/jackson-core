package tools.jackson.core.json.async;

import java.io.IOException;
import java.io.OutputStream;

import tools.jackson.core.*;
import tools.jackson.core.async.CharArrayFeeder;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.sym.ByteQuadsCanonicalizer;
import tools.jackson.core.sym.CharsToNameCanonicalizer;

/**
 * Non-blocking parser implementation for JSON content that takes its input
 * via {@code char[]} passed.
 *<p>
 * Supports full Unicode content since input is already decoded into Java chars.
 * Char offsets are tracked for location reporting.
 */
public class NonBlockingCharArrayJsonParser
    extends NonBlockingJsonCharParserBase
    implements CharArrayFeeder
{
    private static final char[] NO_CHARS = new char[0];

    private char[] _inputBuffer = NO_CHARS;

    public NonBlockingCharArrayJsonParser(ObjectReadContext readCtxt, IOContext ctxt,
            int stdFeatures, int formatReadFeatures,
            ByteQuadsCanonicalizer dummyByteSym, CharsToNameCanonicalizer charSym) {
        super(readCtxt, ctxt, stdFeatures, formatReadFeatures, dummyByteSym, charSym);
    }

    @Override
    public CharArrayFeeder nonBlockingInputFeeder() {
        return this;
    }

    @Override
    public void feedInput(final char[] buf, final int start, final int end) throws JacksonException {
        if (_inputPtr < _inputEnd) {
            _reportError("Still have %d undecoded chars, should not call 'feedInput'", _inputEnd - _inputPtr);
        }
        if (end < start) {
            _reportError("Input end (%d) may not be before start (%d)", end, start);
        }
        if (_endOfInput) {
            _reportError("Already closed, cannot feed more input");
        }
        _currInputProcessed += _origBufferLen;
        _streamReadConstraints.validateDocumentLength(_currInputProcessed);
        _currInputRowStart = start - (_inputEnd - _currInputRowStart);
        _currBufferStart = start;
        _inputBuffer = buf;
        _inputPtr = start;
        _inputEnd = end;
        _origBufferLen = end - start;
    }

    @Override
    public int releaseBuffered(final OutputStream out) throws JacksonException {
        final int avail = _inputEnd - _inputPtr;
        if (avail > 0) {
            // Can't write chars directly to OutputStream; encode as UTF-8
            // This is a best-effort implementation
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
