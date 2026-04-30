package tools.jackson.core.json.async;

import java.io.OutputStream;
import java.util.Arrays;

import tools.jackson.core.*;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.io.CharTypes;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.sym.ByteQuadsCanonicalizer;
import tools.jackson.core.sym.CharsToNameCanonicalizer;
import tools.jackson.core.util.InternalJacksonUtil;
import tools.jackson.core.util.VersionUtil;

import static tools.jackson.core.JsonTokenId.*;

/**
 * Non-blocking parser base class for JSON content read from {@code char[]}-based
 * or {@link java.nio.CharBuffer}-based sources. Tracks character (not byte) offsets
 * for location reporting.
 *<p>
 * Supports full Unicode content since input is already decoded into Java chars.
 */
public abstract class NonBlockingJsonCharParserBase
    extends NonBlockingJsonParserBase
{
    private final static int FEAT_MASK_TRAILING_COMMA = JsonReadFeature.ALLOW_TRAILING_COMMA.getMask();
    private final static int FEAT_MASK_LEADING_ZEROS = JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.getMask();
    private final static int FEAT_MASK_ALLOW_MISSING = JsonReadFeature.ALLOW_MISSING_VALUES.getMask();
    private final static int FEAT_MASK_ALLOW_SINGLE_QUOTES = JsonReadFeature.ALLOW_SINGLE_QUOTES.getMask();
    private final static int FEAT_MASK_ALLOW_UNQUOTED_NAMES = JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES.getMask();
    private final static int FEAT_MASK_ALLOW_JAVA_COMMENTS = JsonReadFeature.ALLOW_JAVA_COMMENTS.getMask();
    private final static int FEAT_MASK_ALLOW_YAML_COMMENTS = JsonReadFeature.ALLOW_YAML_COMMENTS.getMask();

    // Latin1 codes suffice for structural parsing; chars > 255 are directly valid
    protected final static int[] _icLatin1 = CharTypes.getInputCodeLatin1();

    /*
    /**********************************************************************
    /* Symbol handling (char-based)
    /**********************************************************************
     */

    /**
     * Char-based symbol table used for property name canonicalization.
     */
    protected final CharsToNameCanonicalizer _charSymbols;

    /**
     * Buffer for accumulating property name characters during parsing.
     */
    protected char[] _nameBuffer = new char[64];

    /**
     * Number of chars accumulated in {@link #_nameBuffer}.
     */
    protected int _nameLen;

    /**
     * Running hash for the name being accumulated.
     */
    protected int _nameHash;

    /*
    /**********************************************************************
    /* Input source config
    /**********************************************************************
     */

    /**
     * Total number of chars in the current input buffer segment, used for
     * updating location info when a new segment is fed.
     */
    protected int _origBufferLen;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    protected NonBlockingJsonCharParserBase(ObjectReadContext readCtxt, IOContext ctxt,
            int stdFeatures, int formatFeatures,
            ByteQuadsCanonicalizer dummyByteSym, CharsToNameCanonicalizer charSym)
    {
        super(readCtxt, ctxt, stdFeatures, formatFeatures, dummyByteSym);
        _charSymbols = charSym;
    }

    /*
    /**********************************************************************
    /* Overrides: symbol handling, location
    /**********************************************************************
     */

    @Override
    public boolean willInternPropertyNames() {
        return _charSymbols.willInternStrings();
    }

    @Override
    protected void _releaseBuffers() throws JacksonException {
        super._releaseBuffers();
        _charSymbols.release();
    }

    @Override
    public TokenStreamLocation currentLocation() {
        int col = _inputPtr - _currInputRowStart + 1; // 1-based
        int row = Math.max(_currInputRow, _currInputRowAlt);
        return new TokenStreamLocation(_contentReference(),
                -1L, _currInputProcessed + (_inputPtr - _currBufferStart), // bytes=-1, chars
                row, col);
    }

    @Override
    protected TokenStreamLocation _currentLocationMinusOne() {
        final int prevInputPtr = _inputPtr - 1;
        int row = Math.max(_currInputRow, _currInputRowAlt);
        final int col = prevInputPtr - _currInputRowStart + 1; // 1-based
        return new TokenStreamLocation(_contentReference(),
                -1L, _currInputProcessed + (prevInputPtr - _currBufferStart), // bytes=-1, chars
                row, col);
    }

    @Override
    public TokenStreamLocation currentTokenLocation() {
        return new TokenStreamLocation(_contentReference(),
                -1L, _tokenInputTotal, _tokenInputRow, _tokenInputCol);
    }

    /*
    /**********************************************************************
    /* NonBlockingInputFeeder implementation
    /**********************************************************************
     */

    public final boolean needMoreInput() {
        return (_inputPtr >= _inputEnd) && !_endOfInput;
    }

    public void endOfInput() {
        _endOfInput = true;
    }

    /*
    /**********************************************************************
    /* Abstract methods provided by subclasses
    /**********************************************************************
     */

    /**
     * @return next char from the input buffer as an int (0-65535)
     */
    protected abstract int getNextCharFromBuffer();

    /**
     * @param ptr index into the input buffer
     * @return char at given position as an int (0-65535)
     */
    protected abstract int getCharFromBuffer(int ptr);

    @Override
    public abstract int releaseBuffered(OutputStream out) throws JacksonException;

    /*
    /**********************************************************************
    /* Unused abstract methods from parent (byte-based): provide stubs
    /**********************************************************************
     */

    @Override
    protected byte getNextSignedByteFromBuffer() {
        VersionUtil.throwInternal();
        return 0;
    }

    @Override
    protected int getNextUnsignedByteFromBuffer() {
        VersionUtil.throwInternal();
        return 0;
    }

    @Override
    protected byte getByteFromBuffer(int ptr) {
        VersionUtil.throwInternal();
        return 0;
    }

    /*
    /**********************************************************************
    /* Main-level decoding
    /**********************************************************************
     */

    @Override
    protected char _decodeEscaped() throws JacksonException {
        VersionUtil.throwInternal();
        return ' ';
    }

    @Override
    public JsonToken nextToken() throws JacksonException {
        if (_inputPtr >= _inputEnd) {
            if (_closed) {
                return null;
            }
            if (_endOfInput) {
                if (_currToken == JsonToken.NOT_AVAILABLE) {
                    return _finishTokenWithEOF();
                }
                return _eofAsNextToken();
            }
            return JsonToken.NOT_AVAILABLE;
        }
        if (_currToken == JsonToken.NOT_AVAILABLE) {
            return _finishToken();
        }

        _numTypesValid = NR_UNKNOWN;
        _tokenInputTotal = _currInputProcessed + (_inputPtr - _currBufferStart);
        _binaryValue = null;
        int ch = getNextCharFromBuffer();

        switch (_majorState) {
        case MAJOR_INITIAL:
            return _startDocument(ch);
        case MAJOR_ROOT:
            return _startValue(ch);
        case MAJOR_OBJECT_PROPERTY_FIRST:
            return _startName(ch);
        case MAJOR_OBJECT_PROPERTY_NEXT:
            return _startNameAfterComma(ch);
        case MAJOR_OBJECT_VALUE:
            return _startValueExpectColon(ch);
        case MAJOR_ARRAY_ELEMENT_FIRST:
            return _startValue(ch);
        case MAJOR_ARRAY_ELEMENT_NEXT:
            return _startValueExpectComma(ch);
        default:
        }
        VersionUtil.throwInternal();
        return null;
    }

    protected final JsonToken _finishToken() throws JacksonException {
        switch (_minorState) {
        case MINOR_ROOT_BOM:
            // char BOM (U+FEFF): skip and restart document
            return _startDocument(getNextCharFromBuffer());
        case MINOR_PROPERTY_LEADING_WS:
            return _startName(getNextCharFromBuffer());
        case MINOR_PROPERTY_LEADING_COMMA:
            return _startNameAfterComma(getNextCharFromBuffer());

        case MINOR_PROPERTY_NAME:
            return _parseEscapedName(_nameLen, _nameHash);
        case MINOR_PROPERTY_NAME_ESCAPE:
            return _finishPropertyWithEscape();
        case MINOR_PROPERTY_APOS_NAME:
            return _finishAposName(_nameLen, _nameHash);
        case MINOR_PROPERTY_UNQUOTED_NAME:
            return _finishUnquotedName(_nameLen, _nameHash);

        case MINOR_VALUE_LEADING_WS:
            return _startValue(getNextCharFromBuffer());
        case MINOR_VALUE_WS_AFTER_COMMA:
            return _startValueAfterComma(getNextCharFromBuffer());
        case MINOR_VALUE_EXPECTING_COMMA:
            return _startValueExpectComma(getNextCharFromBuffer());
        case MINOR_VALUE_EXPECTING_COLON:
            return _startValueExpectColon(getNextCharFromBuffer());

        case MINOR_VALUE_TOKEN_NULL:
            return _finishKeywordToken("null", _pending32, JsonToken.VALUE_NULL);
        case MINOR_VALUE_TOKEN_TRUE:
            return _finishKeywordToken("true", _pending32, JsonToken.VALUE_TRUE);
        case MINOR_VALUE_TOKEN_FALSE:
            return _finishKeywordToken("false", _pending32, JsonToken.VALUE_FALSE);
        case MINOR_VALUE_TOKEN_NON_STD:
            return _finishNonStdToken(_nonStdTokenType, _pending32);

        case MINOR_NUMBER_PLUS:
            return _finishNumberPlus(getNextCharFromBuffer());
        case MINOR_NUMBER_MINUS:
            return _finishNumberMinus(getNextCharFromBuffer());
        case MINOR_NUMBER_ZERO:
            return _finishNumberLeadingZeroes();
        case MINOR_NUMBER_MINUSZERO:
            return _finishNumberLeadingNegZeroes();
        case MINOR_NUMBER_INTEGER_DIGITS:
            return _finishNumberIntegralPart(_textBuffer.getBufferWithoutReset(),
                    _textBuffer.getCurrentSegmentSize());
        case MINOR_NUMBER_FRACTION_DIGITS:
            return _finishFloatFraction();
        case MINOR_NUMBER_EXPONENT_MARKER:
            return _finishFloatExponent(true, getNextCharFromBuffer());
        case MINOR_NUMBER_EXPONENT_DIGITS:
            return _finishFloatExponent(false, getNextCharFromBuffer());

        case MINOR_VALUE_STRING:
            return _finishRegularString();
        case MINOR_VALUE_STRING_ESCAPE:
            {
                int c = _decodeSplitEscaped(_quoted32, _quotedDigits);
                if (c < 0) {
                    return JsonToken.NOT_AVAILABLE;
                }
                _textBuffer.append((char) c);
            }
            if (_minorStateAfterSplit == MINOR_VALUE_APOS_STRING) {
                return _finishAposString();
            }
            return _finishRegularString();

        case MINOR_VALUE_APOS_STRING:
            return _finishAposString();

        case MINOR_VALUE_TOKEN_ERROR:
            return _finishErrorToken();

        case MINOR_COMMENT_LEADING_SLASH:
            return _startSlashComment(_pending32);
        case MINOR_COMMENT_CLOSING_ASTERISK:
            return _finishCComment(_pending32, true);
        case MINOR_COMMENT_C:
            return _finishCComment(_pending32, false);
        case MINOR_COMMENT_CPP:
            return _finishCppComment(_pending32);
        case MINOR_COMMENT_YAML:
            return _finishHashComment(_pending32);
        }
        VersionUtil.throwInternal();
        return null;
    }

    protected final JsonToken _finishTokenWithEOF() throws JacksonException {
        JsonToken t = _currToken;
        switch (_minorState) {
        case MINOR_ROOT_GOT_SEPARATOR:
            return _eofAsNextToken();
        case MINOR_PROPERTY_LEADING_COMMA:
            _reportInvalidEOF(": expected an Object property name or END_ARRAY", JsonToken.NOT_AVAILABLE);

        case MINOR_VALUE_LEADING_WS:
            return _eofAsNextToken();
        case MINOR_VALUE_EXPECTING_COMMA:
            _reportInvalidEOF(": expected a value token", JsonToken.NOT_AVAILABLE);

        case MINOR_VALUE_TOKEN_NULL:
            return _finishKeywordTokenWithEOF("null", _pending32, JsonToken.VALUE_NULL);
        case MINOR_VALUE_TOKEN_TRUE:
            return _finishKeywordTokenWithEOF("true", _pending32, JsonToken.VALUE_TRUE);
        case MINOR_VALUE_TOKEN_FALSE:
            return _finishKeywordTokenWithEOF("false", _pending32, JsonToken.VALUE_FALSE);
        case MINOR_VALUE_TOKEN_NON_STD:
            return _finishNonStdTokenWithEOF(_nonStdTokenType, _pending32);
        case MINOR_VALUE_TOKEN_ERROR:
            return _finishErrorTokenWithEOF();

        case MINOR_NUMBER_ZERO:
        case MINOR_NUMBER_MINUSZERO:
            return _valueCompleteInt(0, "0");
        case MINOR_NUMBER_INTEGER_DIGITS:
            {
                int len = _textBuffer.getCurrentSegmentSize();
                if (_numberNegative) {
                    --len;
                }
                _setIntLength(len);
            }
            return _valueComplete(JsonToken.VALUE_NUMBER_INT);

        case MINOR_NUMBER_FRACTION_DIGITS:
            _expLength = 0;
            // fall through
        case MINOR_NUMBER_EXPONENT_DIGITS:
            return _valueComplete(JsonToken.VALUE_NUMBER_FLOAT);

        case MINOR_NUMBER_EXPONENT_MARKER:
            _reportInvalidEOF(": was expecting fraction after exponent marker", JsonToken.VALUE_NUMBER_FLOAT);

        case MINOR_COMMENT_CLOSING_ASTERISK:
        case MINOR_COMMENT_C:
            _reportInvalidEOF(": was expecting closing '*/' for comment", JsonToken.NOT_AVAILABLE);

        case MINOR_COMMENT_CPP:
        case MINOR_COMMENT_YAML:
            return _eofAsNextToken();

        default:
        }
        _reportInvalidEOF(": was expecting rest of token (internal state: "+_minorState+")", _currToken);
        return t;
    }

    /*
    /**********************************************************************
    /* Second-level decoding, root level
    /**********************************************************************
     */

    private final JsonToken _startDocument(int ch) throws JacksonException {
        // Check for Unicode BOM (U+FEFF)
        if (ch == 0xFEFF && (_minorState != MINOR_ROOT_BOM)) {
            // Skip BOM; if no more input, set state to re-enter
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_ROOT_BOM;
                if (_endOfInput) {
                    return _eofAsNextToken();
                }
                return _updateTokenToNA();
            }
            ch = getNextCharFromBuffer();
        }
        while (ch <= 0x0020) {
            if (ch != INT_SPACE) {
                if (ch == INT_LF) {
                    ++_currInputRow;
                    _currInputRowStart = _inputPtr;
                } else if (ch == INT_CR) {
                    ++_currInputRowAlt;
                    _currInputRowStart = _inputPtr;
                } else if (ch != INT_TAB) {
                    _reportInvalidSpace(ch);
                }
            }
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_ROOT_GOT_SEPARATOR;
                if (_closed) {
                    return null;
                }
                if (_endOfInput) {
                    return _eofAsNextToken();
                }
                return JsonToken.NOT_AVAILABLE;
            }
            ch = getNextCharFromBuffer();
        }
        return _startValue(ch);
    }

    /*
    /**********************************************************************
    /* Second-level decoding, name decoding
    /**********************************************************************
     */

    private final JsonToken _startName(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_PROPERTY_LEADING_WS;
                return _currToken;
            }
        }
        _updateTokenLocation();
        if (ch != INT_QUOTE) {
            if (ch == INT_RCURLY) {
                return _closeObjectScope();
            }
            return _handleOddName(ch);
        }
        // Fast path: try to parse the whole name from the current buffer
        if ((_inputPtr + 13) <= _inputEnd) {
            String n = _fastParseName();
            if (n != null) {
                return _fieldComplete(n);
            }
        }
        _nameLen = 0;
        _nameHash = _charSymbols.hashSeed();
        return _parseEscapedName(0, _nameHash);
    }

    private final JsonToken _startNameAfterComma(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_PROPERTY_LEADING_COMMA;
                return _currToken;
            }
        }
        if (ch != INT_COMMA) {
            if (ch == INT_RCURLY) {
                return _closeObjectScope();
            }
            if (ch == INT_HASH) {
                return _finishHashComment(MINOR_PROPERTY_LEADING_COMMA);
            }
            if (ch == INT_SLASH) {
                return _startSlashComment(MINOR_PROPERTY_LEADING_COMMA);
            }
            _reportUnexpectedChar(ch, "was expecting comma to separate "+_streamReadContext.typeDesc()+" entries");
        }
        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _minorState = MINOR_PROPERTY_LEADING_WS;
            return _updateTokenToNA();
        }
        ch = getCharFromBuffer(ptr);
        _inputPtr = ptr + 1;
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_PROPERTY_LEADING_WS;
                return _currToken;
            }
        }
        _updateTokenLocation();
        if (ch != INT_QUOTE) {
            if (ch == INT_RCURLY) {
                if ((_formatReadFeatures & FEAT_MASK_TRAILING_COMMA) != 0) {
                    return _closeObjectScope();
                }
            }
            return _handleOddName(ch);
        }
        if ((_inputPtr + 13) <= _inputEnd) {
            String n = _fastParseName();
            if (n != null) {
                return _fieldComplete(n);
            }
        }
        _nameLen = 0;
        _nameHash = _charSymbols.hashSeed();
        return _parseEscapedName(0, _nameHash);
    }

    /*
    /**********************************************************************
    /* Second-level decoding, value decoding
    /**********************************************************************
     */

    private final JsonToken _startValue(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_LEADING_WS;
                return _currToken;
            }
        }
        _updateTokenLocation();
        _streamReadContext.expectComma();

        if (ch == INT_QUOTE) {
            return _startString();
        }
        switch (ch) {
        case '#':
            return _finishHashComment(MINOR_VALUE_LEADING_WS);
        case '+':
            return _startPositiveNumber();
        case '-':
            return _startNegativeNumber();
        case '/':
            return _startSlashComment(MINOR_VALUE_LEADING_WS);
        case '.':
            if (isEnabled(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)) {
                return _startFloatThatStartsWithPeriod();
            }
            break;
        case '0':
            return _startNumberLeadingZero();
        case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
            return _startPositiveNumber(ch);
        case 'f':
            return _startFalseToken();
        case 'n':
            return _startNullToken();
        case 't':
            return _startTrueToken();
        case '[':
            return _startArrayScope();
        case INT_RBRACKET:
            return _closeArrayScope();
        case '{':
            return _startObjectScope();
        case INT_RCURLY:
            return _closeObjectScope();
        default:
        }
        return _startUnexpectedValue(false, ch);
    }

    private final JsonToken _startValueExpectComma(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_EXPECTING_COMMA;
                return _currToken;
            }
        }
        if (ch != INT_COMMA) {
            if (ch == INT_RBRACKET) {
                return _closeArrayScope();
            }
            if (ch == INT_RCURLY) {
                return _closeObjectScope();
            }
            if (ch == INT_SLASH) {
                return _startSlashComment(MINOR_VALUE_EXPECTING_COMMA);
            }
            if (ch == INT_HASH) {
                return _finishHashComment(MINOR_VALUE_EXPECTING_COMMA);
            }
            _reportUnexpectedChar(ch, "was expecting comma to separate "+_streamReadContext.typeDesc()+" entries");
        }
        _streamReadContext.expectComma();

        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _minorState = MINOR_VALUE_WS_AFTER_COMMA;
            return _updateTokenToNA();
        }
        ch = getCharFromBuffer(ptr);
        _inputPtr = ptr + 1;
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_WS_AFTER_COMMA;
                return _currToken;
            }
        }
        _updateTokenLocation();
        if (ch == INT_QUOTE) {
            return _startString();
        }
        switch (ch) {
        case '#':
            return _finishHashComment(MINOR_VALUE_WS_AFTER_COMMA);
        case '+':
            return _startPositiveNumber();
        case '-':
            return _startNegativeNumber();
        case '/':
            return _startSlashComment(MINOR_VALUE_WS_AFTER_COMMA);
        case '0':
            return _startNumberLeadingZero();
        case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
            return _startPositiveNumber(ch);
        case 'f':
            return _startFalseToken();
        case 'n':
            return _startNullToken();
        case 't':
            return _startTrueToken();
        case '[':
            return _startArrayScope();
        case INT_RBRACKET:
            if ((_formatReadFeatures & FEAT_MASK_TRAILING_COMMA) != 0) {
                return _closeArrayScope();
            }
            break;
        case '{':
            return _startObjectScope();
        case INT_RCURLY:
            if ((_formatReadFeatures & FEAT_MASK_TRAILING_COMMA) != 0) {
                return _closeObjectScope();
            }
            break;
        default:
        }
        return _startUnexpectedValue(true, ch);
    }

    private final JsonToken _startValueExpectColon(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_EXPECTING_COLON;
                return _currToken;
            }
        }
        if (ch != INT_COLON) {
            if (ch == INT_SLASH) {
                return _startSlashComment(MINOR_VALUE_EXPECTING_COLON);
            }
            if (ch == INT_HASH) {
                return _finishHashComment(MINOR_VALUE_EXPECTING_COLON);
            }
            _reportUnexpectedChar(ch, "was expecting a colon to separate field name and value");
        }
        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _minorState = MINOR_VALUE_LEADING_WS;
            return _updateTokenToNA();
        }
        ch = getCharFromBuffer(ptr);
        _inputPtr = ptr + 1;
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_LEADING_WS;
                return _currToken;
            }
        }
        _updateTokenLocation();
        if (ch == INT_QUOTE) {
            return _startString();
        }
        switch (ch) {
        case '#':
            return _finishHashComment(MINOR_VALUE_LEADING_WS);
        case '+':
            return _startPositiveNumber();
        case '-':
            return _startNegativeNumber();
        case '/':
            return _startSlashComment(MINOR_VALUE_LEADING_WS);
        case '0':
            return _startNumberLeadingZero();
        case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
            return _startPositiveNumber(ch);
        case 'f':
            return _startFalseToken();
        case 'n':
            return _startNullToken();
        case 't':
            return _startTrueToken();
        case '[':
            return _startArrayScope();
        case '{':
            return _startObjectScope();
        default:
        }
        return _startUnexpectedValue(false, ch);
    }

    private final JsonToken _startValueAfterComma(int ch) throws JacksonException {
        if (ch <= 0x0020) {
            ch = _skipWS(ch);
            if (ch <= 0) {
                _minorState = MINOR_VALUE_WS_AFTER_COMMA;
                return _currToken;
            }
        }
        _updateTokenLocation();
        if (ch == INT_QUOTE) {
            return _startString();
        }
        switch (ch) {
        case '#':
            return _finishHashComment(MINOR_VALUE_WS_AFTER_COMMA);
        case '+':
            return _startPositiveNumber();
        case '-':
            return _startNegativeNumber();
        case '/':
            return _startSlashComment(MINOR_VALUE_WS_AFTER_COMMA);
        case '0':
            return _startNumberLeadingZero();
        case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
            return _startPositiveNumber(ch);
        case 'f':
            return _startFalseToken();
        case 'n':
            return _startNullToken();
        case 't':
            return _startTrueToken();
        case '[':
            return _startArrayScope();
        case INT_RBRACKET:
            if ((_formatReadFeatures & FEAT_MASK_TRAILING_COMMA) != 0) {
                return _closeArrayScope();
            }
            break;
        case '{':
            return _startObjectScope();
        case INT_RCURLY:
            if ((_formatReadFeatures & FEAT_MASK_TRAILING_COMMA) != 0) {
                return _closeObjectScope();
            }
            break;
        default:
        }
        return _startUnexpectedValue(true, ch);
    }

    protected JsonToken _startUnexpectedValue(boolean leadingComma, int ch) throws JacksonException {
        switch (ch) {
        case INT_RBRACKET:
            if (!_streamReadContext.inArray()) {
                break;
            }
            // fall through
        case ',':
            if (!_streamReadContext.inRoot()) {
                if ((_formatReadFeatures & FEAT_MASK_ALLOW_MISSING) != 0) {
                    --_inputPtr;
                    return _valueComplete(JsonToken.VALUE_NULL);
                }
            }
            // fall through
        case INT_RCURLY:
            break;
        case '\'':
            if ((_formatReadFeatures & FEAT_MASK_ALLOW_SINGLE_QUOTES) != 0) {
                return _startAposString();
            }
            break;
        case '+':
            return _finishNonStdToken(NON_STD_TOKEN_PLUS_INFINITY, 1);
        case 'N':
            return _finishNonStdToken(NON_STD_TOKEN_NAN, 1);
        case 'I':
            return _finishNonStdToken(NON_STD_TOKEN_INFINITY, 1);
        }
        _reportUnexpectedChar(ch, "expected a valid value "+_validJsonValueList());
        return null;
    }

    /*
    /**********************************************************************
    /* Second-level decoding, whitespace and comments
    /**********************************************************************
     */

    private final int _skipWS(int ch) throws JacksonException {
        do {
            if (ch != INT_SPACE) {
                if (ch == INT_LF) {
                    ++_currInputRow;
                    _currInputRowStart = _inputPtr;
                } else if (ch == INT_CR) {
                    ++_currInputRowAlt;
                    _currInputRowStart = _inputPtr;
                } else if (ch != INT_TAB && !_isAllowedCtrlCharRS(ch)) {
                    _reportInvalidSpace(ch);
                }
            }
            if (_inputPtr >= _inputEnd) {
                _updateTokenToNA();
                return 0;
            }
            ch = getNextCharFromBuffer();
        } while (ch <= 0x0020);
        return ch;
    }

    private final JsonToken _startSlashComment(int fromMinorState) throws JacksonException {
        if ((_formatReadFeatures & FEAT_MASK_ALLOW_JAVA_COMMENTS) == 0) {
            _reportUnexpectedChar('/', "maybe a (non-standard) comment? (not recognized as one since Feature 'ALLOW_COMMENTS' not enabled for parser)");
        }
        if (_inputPtr >= _inputEnd) {
            _pending32 = fromMinorState;
            _minorState = MINOR_COMMENT_LEADING_SLASH;
            return _updateTokenToNA();
        }
        int ch = getNextCharFromBuffer();
        if (ch == INT_ASTERISK) {
            return _finishCComment(fromMinorState, false);
        }
        if (ch == INT_SLASH) {
            return _finishCppComment(fromMinorState);
        }
        _reportUnexpectedChar(ch, "was expecting either '*' or '/' for a comment");
        return null;
    }

    private final JsonToken _finishHashComment(int fromMinorState) throws JacksonException {
        if ((_formatReadFeatures & FEAT_MASK_ALLOW_YAML_COMMENTS) == 0) {
            _reportUnexpectedChar('#', "maybe a (non-standard) comment? (not recognized as one since Feature 'ALLOW_YAML_COMMENTS' not enabled for parser)");
        }
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_COMMENT_YAML;
                _pending32 = fromMinorState;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch < 0x020) {
                if (ch == INT_LF) {
                    ++_currInputRow;
                    _currInputRowStart = _inputPtr;
                    break;
                } else if (ch == INT_CR) {
                    ++_currInputRowAlt;
                    _currInputRowStart = _inputPtr;
                    break;
                } else if (ch != INT_TAB) {
                    _reportInvalidSpace(ch);
                }
            }
        }
        return _startAfterComment(fromMinorState);
    }

    private final JsonToken _finishCppComment(int fromMinorState) throws JacksonException {
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_COMMENT_CPP;
                _pending32 = fromMinorState;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch < 0x020) {
                if (ch == INT_LF) {
                    ++_currInputRow;
                    _currInputRowStart = _inputPtr;
                    break;
                } else if (ch == INT_CR) {
                    ++_currInputRowAlt;
                    _currInputRowStart = _inputPtr;
                    break;
                } else if (ch != INT_TAB) {
                    _reportInvalidSpace(ch);
                }
            }
        }
        return _startAfterComment(fromMinorState);
    }

    private final JsonToken _finishCComment(int fromMinorState, boolean gotStar) throws JacksonException {
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = gotStar ? MINOR_COMMENT_CLOSING_ASTERISK : MINOR_COMMENT_C;
                _pending32 = fromMinorState;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch < 0x020) {
                if (ch == INT_LF) {
                    ++_currInputRow;
                    _currInputRowStart = _inputPtr;
                } else if (ch == INT_CR) {
                    ++_currInputRowAlt;
                    _currInputRowStart = _inputPtr;
                } else if (ch != INT_TAB) {
                    _reportInvalidSpace(ch);
                }
            } else if (ch == INT_ASTERISK) {
                gotStar = true;
                continue;
            } else if (ch == INT_SLASH) {
                if (gotStar) {
                    break;
                }
            }
            gotStar = false;
        }
        return _startAfterComment(fromMinorState);
    }

    private final JsonToken _startAfterComment(int fromMinorState) throws JacksonException {
        if (_inputPtr >= _inputEnd) {
            _minorState = fromMinorState;
            return _updateTokenToNA();
        }
        int ch = getNextCharFromBuffer();
        switch (fromMinorState) {
        case MINOR_PROPERTY_LEADING_WS:
            return _startName(ch);
        case MINOR_PROPERTY_LEADING_COMMA:
            return _startNameAfterComma(ch);
        case MINOR_VALUE_LEADING_WS:
            return _startValue(ch);
        case MINOR_VALUE_EXPECTING_COMMA:
            return _startValueExpectComma(ch);
        case MINOR_VALUE_EXPECTING_COLON:
            return _startValueExpectColon(ch);
        case MINOR_VALUE_WS_AFTER_COMMA:
            return _startValueAfterComma(ch);
        default:
        }
        VersionUtil.throwInternal();
        return null;
    }

    /*
    /**********************************************************************
    /* Tertiary decoding, simple tokens
    /**********************************************************************
     */

    protected JsonToken _startFalseToken() throws JacksonException {
        int ptr = _inputPtr;
        if ((ptr + 4) < _inputEnd) {
            if ((getCharFromBuffer(ptr++) == 'a')
                    && (getCharFromBuffer(ptr++) == 'l')
                    && (getCharFromBuffer(ptr++) == 's')
                    && (getCharFromBuffer(ptr++) == 'e')) {
                int ch = getCharFromBuffer(ptr);
                if (ch < INT_0 || (ch | 0x20) == INT_RCURLY) {
                    _inputPtr = ptr;
                    return _valueComplete(JsonToken.VALUE_FALSE);
                }
            }
        }
        _minorState = MINOR_VALUE_TOKEN_FALSE;
        return _finishKeywordToken("false", 1, JsonToken.VALUE_FALSE);
    }

    protected JsonToken _startTrueToken() throws JacksonException {
        int ptr = _inputPtr;
        if ((ptr + 3) < _inputEnd) {
            if ((getCharFromBuffer(ptr++) == 'r')
                    && (getCharFromBuffer(ptr++) == 'u')
                    && (getCharFromBuffer(ptr++) == 'e')) {
                int ch = getCharFromBuffer(ptr);
                if (ch < INT_0 || (ch | 0x20) == INT_RCURLY) {
                    _inputPtr = ptr;
                    return _valueComplete(JsonToken.VALUE_TRUE);
                }
            }
        }
        _minorState = MINOR_VALUE_TOKEN_TRUE;
        return _finishKeywordToken("true", 1, JsonToken.VALUE_TRUE);
    }

    protected JsonToken _startNullToken() throws JacksonException {
        int ptr = _inputPtr;
        if ((ptr + 3) < _inputEnd) {
            if ((getCharFromBuffer(ptr++) == 'u')
                    && (getCharFromBuffer(ptr++) == 'l')
                    && (getCharFromBuffer(ptr++) == 'l')) {
                int ch = getCharFromBuffer(ptr);
                if (ch < INT_0 || (ch | 0x20) == INT_RCURLY) {
                    _inputPtr = ptr;
                    return _valueComplete(JsonToken.VALUE_NULL);
                }
            }
        }
        _minorState = MINOR_VALUE_TOKEN_NULL;
        return _finishKeywordToken("null", 1, JsonToken.VALUE_NULL);
    }

    protected JsonToken _finishKeywordToken(String expToken, int matched,
                                            JsonToken result) throws JacksonException {
        final int end = expToken.length();
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _pending32 = matched;
                return _updateTokenToNA();
            }
            int ch = getCharFromBuffer(_inputPtr);
            if (matched == end) {
                if (ch < INT_0 || (ch | 0x20) == INT_RCURLY) {
                    return _valueComplete(result);
                }
                break;
            }
            if (ch != expToken.charAt(matched)) {
                break;
            }
            ++matched;
            ++_inputPtr;
        }
        _minorState = MINOR_VALUE_TOKEN_ERROR;
        _textBuffer.resetWithCopy(expToken, 0, matched);
        return _finishErrorToken();
    }

    protected JsonToken _finishKeywordTokenWithEOF(String expToken, int matched,
            JsonToken result) throws JacksonException {
        if (matched == expToken.length()) {
            return _updateToken(result);
        }
        _textBuffer.resetWithCopy(expToken, 0, matched);
        return _finishErrorTokenWithEOF();
    }

    protected JsonToken _finishNonStdToken(int type, int matched) throws JacksonException {
        final String expToken = _nonStdToken(type);
        final int end = expToken.length();
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _nonStdTokenType = type;
                _pending32 = matched;
                _minorState = MINOR_VALUE_TOKEN_NON_STD;
                return _updateTokenToNA();
            }
            int ch = getCharFromBuffer(_inputPtr);
            if (matched == end) {
                if (ch < INT_0 || (ch | 0x20) == INT_RCURLY) {
                    return _valueNonStdNumberComplete(type);
                }
                break;
            }
            if (ch != expToken.charAt(matched)) {
                break;
            }
            ++matched;
            ++_inputPtr;
        }
        _minorState = MINOR_VALUE_TOKEN_ERROR;
        _textBuffer.resetWithCopy(expToken, 0, matched);
        return _finishErrorToken();
    }

    protected JsonToken _finishNonStdTokenWithEOF(int type, int matched) throws JacksonException {
        final String expToken = _nonStdToken(type);
        if (matched == expToken.length()) {
            return _valueNonStdNumberComplete(type);
        }
        _textBuffer.resetWithCopy(expToken, 0, matched);
        return _finishErrorTokenWithEOF();
    }

    protected JsonToken _finishErrorToken() throws JacksonException {
        while (_inputPtr < _inputEnd) {
            char ch = (char) getNextCharFromBuffer();
            if (Character.isJavaIdentifierPart(ch)) {
                _textBuffer.append(ch);
                if (_textBuffer.size() < _ioContext.errorReportConfiguration().getMaxErrorTokenLength()) {
                    continue;
                }
            }
            return _reportErrorToken(_textBuffer.contentsAsString());
        }
        return _updateTokenToNA();
    }

    protected JsonToken _finishErrorTokenWithEOF() throws JacksonException {
        return _reportErrorToken(_textBuffer.contentsAsString());
    }

    protected JsonToken _reportErrorToken(String actualToken) throws JacksonException {
        final String fullMsg = String.format("Unrecognized token '%s': was expecting %s",
                _textBuffer.contentsAsString(), _validJsonTokenList());
        throw _constructReadException(fullMsg, currentTokenLocation());
    }

    /*
    /**********************************************************************
    /* Second-level decoding, number decoding
    /**********************************************************************
     */

    protected JsonToken _startFloatThatStartsWithPeriod() throws JacksonException {
        _numberNegative = false;
        _intLength = 0;
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        return _startFloat(outBuf, 0, INT_PERIOD);
    }

    protected JsonToken _startPositiveNumber(int ch) throws JacksonException {
        _numberNegative = false;
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        outBuf[0] = (char) ch;
        if (_inputPtr >= _inputEnd) {
            _minorState = MINOR_NUMBER_INTEGER_DIGITS;
            _textBuffer.setCurrentLength(1);
            return _updateTokenToNA();
        }
        int outPtr = 1;
        ch = getCharFromBuffer(_inputPtr);
        while (true) {
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    _setIntLength(outPtr);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    _setIntLength(outPtr);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
            if (++_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_INTEGER_DIGITS;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            ch = getCharFromBuffer(_inputPtr);
        }
        _setIntLength(outPtr);
        _textBuffer.setCurrentLength(outPtr);
        return _valueComplete(JsonToken.VALUE_NUMBER_INT);
    }

    protected JsonToken _startNegativeNumber() throws JacksonException {
        _numberNegative = true;
        if (_inputPtr >= _inputEnd) {
            _minorState = MINOR_NUMBER_MINUS;
            return _updateTokenToNA();
        }
        int ch = getNextCharFromBuffer();
        if (ch <= INT_0) {
            if (ch == INT_0) {
                return _finishNumberLeadingNegZeroes();
            }
            _reportUnexpectedNumberChar(ch, "expected digit (0-9) to follow minus sign, for valid numeric value");
        } else if (ch > INT_9) {
            if (ch == 'I') {
                return _finishNonStdToken(NON_STD_TOKEN_MINUS_INFINITY, 2);
            }
            _reportUnexpectedNumberChar(ch, "expected digit (0-9) to follow minus sign, for valid numeric value");
        }
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        outBuf[0] = '-';
        outBuf[1] = (char) ch;
        if (_inputPtr >= _inputEnd) {
            _minorState = MINOR_NUMBER_INTEGER_DIGITS;
            _textBuffer.setCurrentLength(2);
            _intLength = 1;
            return _updateTokenToNA();
        }
        ch = getCharFromBuffer(_inputPtr);
        int outPtr = 2;
        while (true) {
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    _setIntLength(outPtr - 1);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    _setIntLength(outPtr - 1);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
            if (++_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_INTEGER_DIGITS;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            ch = getCharFromBuffer(_inputPtr);
        }
        _setIntLength(outPtr - 1);
        _textBuffer.setCurrentLength(outPtr);
        return _valueComplete(JsonToken.VALUE_NUMBER_INT);
    }

    protected JsonToken _startPositiveNumber() throws JacksonException {
        _numberNegative = false;
        if (_inputPtr >= _inputEnd) {
            _minorState = MINOR_NUMBER_PLUS;
            return _updateTokenToNA();
        }
        int ch = getNextCharFromBuffer();
        if (ch <= INT_0) {
            if (ch == INT_0) {
                if (!isEnabled(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)) {
                    _reportUnexpectedNumberChar('+', "JSON spec does not allow numbers to have plus signs: enable `JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS` to allow");
                }
                return _finishNumberLeadingPosZeroes();
            }
            _reportUnexpectedNumberChar(ch, "expected digit (0-9) to follow plus sign, for valid numeric value");
        } else if (ch > INT_9) {
            if (ch == 'I') {
                return _finishNonStdToken(NON_STD_TOKEN_PLUS_INFINITY, 2);
            }
            _reportUnexpectedNumberChar(ch, "expected digit (0-9) to follow plus sign, for valid numeric value");
        }
        if (!isEnabled(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)) {
            _reportUnexpectedNumberChar('+', "JSON spec does not allow numbers to have plus signs: enable `JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS` to allow");
        }
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        outBuf[0] = '+';
        outBuf[1] = (char) ch;
        if (_inputPtr >= _inputEnd) {
            _minorState = MINOR_NUMBER_INTEGER_DIGITS;
            _textBuffer.setCurrentLength(2);
            _intLength = 1;
            return _updateTokenToNA();
        }
        ch = getCharFromBuffer(_inputPtr);
        int outPtr = 2;
        while (true) {
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    _setIntLength(outPtr - 1);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    _setIntLength(outPtr - 1);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
            if (++_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_INTEGER_DIGITS;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            ch = getCharFromBuffer(_inputPtr);
        }
        _setIntLength(outPtr - 1);
        _textBuffer.setCurrentLength(outPtr);
        return _valueComplete(JsonToken.VALUE_NUMBER_INT);
    }

    protected JsonToken _startNumberLeadingZero() throws JacksonException {
        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _minorState = MINOR_NUMBER_ZERO;
            return _updateTokenToNA();
        }
        int ch = getCharFromBuffer(ptr++);
        if (ch < INT_0) {
            if (ch == INT_PERIOD) {
                _inputPtr = ptr;
                _intLength = 1;
                char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                outBuf[0] = '0';
                return _startFloat(outBuf, 1, ch);
            }
        } else if (ch > INT_9) {
            if ((ch | 0x20) == INT_e) {
                _inputPtr = ptr;
                _intLength = 1;
                char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                outBuf[0] = '0';
                return _startFloat(outBuf, 1, ch);
            }
            if ((ch | 0x20) != INT_RCURLY) {
                _reportUnexpectedNumberChar(ch,
                        "expected digit (0-9), decimal point (.) or exponent indicator (e/E) to follow '0'");
            }
        } else {
            return _finishNumberLeadingZeroes();
        }
        return _valueCompleteInt(0, "0");
    }

    protected JsonToken _finishNumberMinus(int ch) throws JacksonException {
        return _finishNumberPlusMinus(ch, true);
    }

    protected JsonToken _finishNumberPlus(int ch) throws JacksonException {
        return _finishNumberPlusMinus(ch, false);
    }

    protected JsonToken _finishNumberPlusMinus(final int ch, final boolean negative) throws JacksonException {
        if (ch <= INT_0) {
            if (ch == INT_0) {
                if (negative) {
                    return _finishNumberLeadingNegZeroes();
                } else {
                    if (!isEnabled(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)) {
                        _reportUnexpectedNumberChar('+', "JSON spec does not allow numbers to have plus signs: enable `JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS` to allow");
                    }
                    return _finishNumberLeadingPosZeroes();
                }
            } else if (ch == INT_PERIOD && isEnabled(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)) {
                if (negative) {
                    _inputPtr--;
                    return _finishNumberLeadingNegZeroes();
                } else {
                    if (!isEnabled(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)) {
                        _reportUnexpectedNumberChar('+', "JSON spec does not allow numbers to have plus signs: enable `JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS` to allow");
                    }
                    _inputPtr--;
                    return _finishNumberLeadingPosZeroes();
                }
            }
            final String message = negative ?
                    "expected digit (0-9) to follow minus sign, for valid numeric value" :
                    "expected digit (0-9) for valid numeric value";
            _reportUnexpectedNumberChar(ch, message);
        } else if (ch > INT_9) {
            if (ch == 'I') {
                final int token = negative ? NON_STD_TOKEN_MINUS_INFINITY : NON_STD_TOKEN_PLUS_INFINITY;
                return _finishNonStdToken(token, 2);
            }
            final String message = negative ?
                    "expected digit (0-9) to follow minus sign, for valid numeric value" :
                    "expected digit (0-9) for valid numeric value";
            _reportUnexpectedNumberChar(ch, message);
        }
        if (!negative && !isEnabled(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)) {
            _reportUnexpectedNumberChar('+', "JSON spec does not allow numbers to have plus signs: enable `JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS` to allow");
        }
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        outBuf[0] = negative ? '-' : '+';
        outBuf[1] = (char) ch;
        _intLength = 1;
        return _finishNumberIntegralPart(outBuf, 2);
    }

    protected JsonToken _finishNumberLeadingZeroes() throws JacksonException {
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_ZERO;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                    outBuf[0] = '0';
                    _intLength = 1;
                    return _startFloat(outBuf, 1, ch);
                }
            } else if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                    outBuf[0] = '0';
                    _intLength = 1;
                    return _startFloat(outBuf, 1, ch);
                }
                if ((ch | 0x20) != INT_RCURLY) {
                    _reportUnexpectedNumberChar(ch,
                            "expected digit (0-9), decimal point (.) or exponent indicator (e/E) to follow '0'");
                }
            } else {
                if ((_formatReadFeatures & FEAT_MASK_LEADING_ZEROS) == 0) {
                    _reportInvalidNumber("Leading zeroes not allowed");
                }
                if (ch == INT_0) {
                    continue;
                }
                char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                outBuf[0] = (char) ch;
                _intLength = 1;
                return _finishNumberIntegralPart(outBuf, 1);
            }
            --_inputPtr;
            return _valueCompleteInt(0, "0");
        }
    }

    protected JsonToken _finishNumberLeadingNegZeroes() throws JacksonException {
        return _finishNumberLeadingPosNegZeroes(true);
    }

    protected JsonToken _finishNumberLeadingPosZeroes() throws JacksonException {
        return _finishNumberLeadingPosNegZeroes(false);
    }

    protected JsonToken _finishNumberLeadingPosNegZeroes(final boolean negative) throws JacksonException {
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = negative ? MINOR_NUMBER_MINUSZERO : MINOR_NUMBER_ZERO;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                    outBuf[0] = negative ? '-' : '+';
                    outBuf[1] = '0';
                    _intLength = 1;
                    return _startFloat(outBuf, 2, ch);
                }
            } else if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                    outBuf[0] = negative ? '-' : '+';
                    outBuf[1] = '0';
                    _intLength = 1;
                    return _startFloat(outBuf, 2, ch);
                }
                if ((ch | 0x20) != INT_RCURLY) {
                    _reportUnexpectedNumberChar(ch,
                            "expected digit (0-9), decimal point (.) or exponent indicator (e/E) to follow '0'");
                }
            } else {
                if ((_formatReadFeatures & FEAT_MASK_LEADING_ZEROS) == 0) {
                    _reportInvalidNumber("Leading zeroes not allowed");
                }
                if (ch == INT_0) {
                    continue;
                }
                char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
                outBuf[0] = negative ? '-' : '+';
                outBuf[1] = (char) ch;
                _intLength = 1;
                return _finishNumberIntegralPart(outBuf, 2);
            }
            --_inputPtr;
            return _valueCompleteInt(0, "0");
        }
    }

    protected JsonToken _finishNumberIntegralPart(char[] outBuf, int outPtr) throws JacksonException {
        int negMod = _numberNegative ? -1 : 0;
        int ch;
        while (true) {
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_INTEGER_DIGITS;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            ch = getCharFromBuffer(_inputPtr);
            if (ch < INT_0) {
                if (ch == INT_PERIOD) {
                    _setIntLength(outPtr + negMod);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            if (ch > INT_9) {
                if ((ch | 0x20) == INT_e) {
                    _setIntLength(outPtr + negMod);
                    ++_inputPtr;
                    return _startFloat(outBuf, outPtr, ch);
                }
                break;
            }
            ++_inputPtr;
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
        }
        _setIntLength(outPtr + negMod);
        _textBuffer.setCurrentLength(outPtr);
        if (_streamReadContext.inRoot()) {
            _verifyRootSpace(ch);
        }
        return _valueComplete(JsonToken.VALUE_NUMBER_INT);
    }

    protected JsonToken _startFloat(char[] outBuf, int outPtr, int ch) throws JacksonException {
        int fractLen = 0;
        if (ch == INT_PERIOD) {
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = '.';
            while (true) {
                if (_inputPtr >= _inputEnd) {
                    _textBuffer.setCurrentLength(outPtr);
                    _minorState = MINOR_NUMBER_FRACTION_DIGITS;
                    _setFractLength(fractLen);
                    return _updateTokenToNA();
                }
                ch = getNextCharFromBuffer();
                if (ch < INT_0 || ch > INT_9) {
                    if (fractLen == 0) {
                        if (!isEnabled(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)) {
                            _reportUnexpectedNumberChar(ch, "Decimal point not followed by a digit");
                        }
                    } else if (ch == INT_PERIOD) {
                        _reportUnexpectedNumberChar(ch, "Cannot parse number with more than one decimal point");
                    }
                    break;
                }
                if (outPtr >= outBuf.length) {
                    outBuf = _textBuffer.expandCurrentSegment();
                }
                outBuf[outPtr++] = (char) ch;
                ++fractLen;
            }
        }
        _setFractLength(fractLen);
        int expLen = 0;
        if ((ch | 0x20) == INT_e) {
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
            if (_inputPtr >= _inputEnd) {
                _textBuffer.setCurrentLength(outPtr);
                _minorState = MINOR_NUMBER_EXPONENT_MARKER;
                _expLength = 0;
                return _updateTokenToNA();
            }
            ch = getNextCharFromBuffer();
            if (ch == INT_MINUS || ch == INT_PLUS) {
                if (outPtr >= outBuf.length) {
                    outBuf = _textBuffer.expandCurrentSegment();
                }
                outBuf[outPtr++] = (char) ch;
                if (_inputPtr >= _inputEnd) {
                    _textBuffer.setCurrentLength(outPtr);
                    _minorState = MINOR_NUMBER_EXPONENT_DIGITS;
                    _expLength = 0;
                    return _updateTokenToNA();
                }
                ch = getNextCharFromBuffer();
            }
            while (ch >= INT_0 && ch <= INT_9) {
                ++expLen;
                if (outPtr >= outBuf.length) {
                    outBuf = _textBuffer.expandCurrentSegment();
                }
                outBuf[outPtr++] = (char) ch;
                if (_inputPtr >= _inputEnd) {
                    _textBuffer.setCurrentLength(outPtr);
                    _minorState = MINOR_NUMBER_EXPONENT_DIGITS;
                    _setExpLength(expLen);
                    return _updateTokenToNA();
                }
                ch = getNextCharFromBuffer();
            }
            if (expLen == 0) {
                _reportUnexpectedNumberChar(ch, "Exponent indicator not followed by a digit");
            }
        }
        --_inputPtr;
        _textBuffer.setCurrentLength(outPtr);
        if (_streamReadContext.inRoot()) {
            _verifyRootSpace(ch);
        }
        _setExpLength(expLen);
        return _valueComplete(JsonToken.VALUE_NUMBER_FLOAT);
    }

    protected JsonToken _finishFloatFraction() throws JacksonException {
        int fractLen = _fractLength;
        char[] outBuf = _textBuffer.getBufferWithoutReset();
        int outPtr = _textBuffer.getCurrentSegmentSize();

        int ch = getNextCharFromBuffer();
        boolean loop = true;
        while (loop) {
            if (ch >= INT_0 && ch <= INT_9) {
                ++fractLen;
                if (outPtr >= outBuf.length) {
                    outBuf = _textBuffer.expandCurrentSegment();
                }
                outBuf[outPtr++] = (char) ch;
                if (_inputPtr >= _inputEnd) {
                    _textBuffer.setCurrentLength(outPtr);
                    _setFractLength(fractLen);
                    return JsonToken.NOT_AVAILABLE;
                }
                ch = getNextCharFromBuffer();
            } else if ((ch | 0x22) == 'f') {
                if (_streamReadContext.inRoot()) {
                    --_inputPtr;
                    _reportMissingRootWS(ch);
                }
                _reportUnexpectedNumberChar(ch, "JSON does not support parsing numbers that have 'f' or 'd' suffixes");
            } else if (ch == INT_PERIOD) {
                _reportUnexpectedNumberChar(ch, "Cannot parse number with more than one decimal point");
            } else {
                loop = false;
            }
        }
        if (fractLen == 0) {
            if (!isEnabled(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)) {
                _reportUnexpectedNumberChar(ch, "Decimal point not followed by a digit");
            }
        }
        _setFractLength(fractLen);
        _textBuffer.setCurrentLength(outPtr);

        if ((ch | 0x20) == INT_e) {
            _textBuffer.append((char) ch);
            _expLength = 0;
            if (_inputPtr >= _inputEnd) {
                _minorState = MINOR_NUMBER_EXPONENT_MARKER;
                return JsonToken.NOT_AVAILABLE;
            }
            _minorState = MINOR_NUMBER_EXPONENT_DIGITS;
            return _finishFloatExponent(true, getNextCharFromBuffer());
        }
        --_inputPtr;
        _textBuffer.setCurrentLength(outPtr);
        _expLength = 0;
        if (_streamReadContext.inRoot()) {
            _verifyRootSpace(ch);
        }
        return _valueComplete(JsonToken.VALUE_NUMBER_FLOAT);
    }

    protected JsonToken _finishFloatExponent(boolean checkSign, int ch) throws JacksonException {
        if (checkSign) {
            _minorState = MINOR_NUMBER_EXPONENT_DIGITS;
            if (ch == INT_MINUS || ch == INT_PLUS) {
                _textBuffer.append((char) ch);
                if (_inputPtr >= _inputEnd) {
                    _minorState = MINOR_NUMBER_EXPONENT_DIGITS;
                    _expLength = 0;
                    return JsonToken.NOT_AVAILABLE;
                }
                ch = getNextCharFromBuffer();
            }
        }
        char[] outBuf = _textBuffer.getBufferWithoutReset();
        int outPtr = _textBuffer.getCurrentSegmentSize();
        int expLen = _expLength;

        while (ch >= INT_0 && ch <= INT_9) {
            ++expLen;
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.expandCurrentSegment();
            }
            outBuf[outPtr++] = (char) ch;
            if (_inputPtr >= _inputEnd) {
                _textBuffer.setCurrentLength(outPtr);
                _setExpLength(expLen);
                return JsonToken.NOT_AVAILABLE;
            }
            ch = getNextCharFromBuffer();
        }
        if (expLen == 0) {
            _reportUnexpectedNumberChar(ch, "Exponent indicator not followed by a digit");
        }
        --_inputPtr;
        _textBuffer.setCurrentLength(outPtr);
        if (_streamReadContext.inRoot()) {
            _verifyRootSpace(ch);
        }
        _setExpLength(expLen);
        return _valueComplete(JsonToken.VALUE_NUMBER_FLOAT);
    }

    /*
    /**********************************************************************
    /* Name decoding: fast and slow paths
    /**********************************************************************
     */

    private final String _fastParseName() throws JacksonException {
        final int[] codes = _icLatin1;
        int ptr = _inputPtr;
        int hash = _charSymbols.hashSeed();

        // Try to parse up to 12 chars quickly
        while (true) {
            int ch = getCharFromBuffer(ptr);
            if (ch == INT_QUOTE) {
                final int start = _inputPtr;
                _inputPtr = ptr + 1;
                return _charSymbols.findSymbol(_inputBuffer_forName(), start, ptr - start, hash);
            }
            if (ch > 127 || codes[ch] != 0) {
                return null; // need slow path for escapes or non-ASCII
            }
            hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
            ++ptr;
            if (ptr >= _inputEnd) {
                return null;
            }
        }
    }

    /**
     * Returns a char[] view of the current input buffer for use with
     * CharsToNameCanonicalizer. Subclasses must provide this.
     */
    protected abstract char[] _inputBuffer_forName();

    private final JsonToken _parseEscapedName(int nameLen, int hash) throws JacksonException {
        final int[] codes = _icLatin1;

        while (true) {
            if (_inputPtr >= _inputEnd) {
                _nameLen = nameLen;
                _nameHash = hash;
                _minorState = MINOR_PROPERTY_NAME;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();

            // Fast common case: ASCII, not special
            if (ch <= 127 && codes[ch] == 0) {
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = (char) ch;
                continue;
            }

            if (ch == INT_QUOTE) {
                // End of name
                break;
            }

            if (ch > 127) {
                // High char: valid in name, add directly
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = (char) ch;
                continue;
            }

            if (ch != INT_BACKSLASH) {
                _throwUnquotedSpace(ch, "name");
            }
            // Escape sequence
            ch = _decodeCharEscape();
            if (ch < 0) {
                _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                _minorStateAfterSplit = MINOR_PROPERTY_NAME;
                _nameLen = nameLen;
                _nameHash = hash;
                return _updateTokenToNA();
            }
            // Handle surrogate pairs
            if (ch >= 0xD800 && ch <= 0xDBFF) {
                int remaining = _inputEnd - _inputPtr;
                if (remaining >= 6) {
                    if (getCharFromBuffer(_inputPtr) != INT_BACKSLASH) {
                        _reportError("Broken surrogate pair in property name: expected '\\' to start low surrogate, got 0x"
                                + Integer.toHexString(getCharFromBuffer(_inputPtr)));
                    }
                    ++_inputPtr;
                    int lo = _decodeFastCharEscape();
                    ch = _decodeSurrogate(ch, lo);
                } else {
                    _pendingSurrogateInName = ch;
                    _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                    _minorStateAfterSplit = MINOR_PROPERTY_NAME;
                    _nameLen = nameLen;
                    _nameHash = hash;
                    _quoted32 = 0;
                    _quotedDigits = -2;
                    return _updateTokenToNA();
                }
            } else if (ch >= 0xDC00 && ch <= 0xDFFF) {
                _reportUnexpectedLowSurrogate(ch);
            }

            // If it's a supplementary (non-BMP) char, store as surrogate pair
            if (ch > 0xFFFF) {
                int sup = ch - 0x10000;
                char hi = (char) (0xD800 | (sup >> 10));
                char lo = (char) (0xDC00 | (sup & 0x3FF));
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + hi;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = hi;
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + lo;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = lo;
            } else {
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = (char) ch;
            }
        }

        _streamReadConstraints.validateNameLength(nameLen);
        String name = _charSymbols.findSymbol(_nameBuffer, 0, nameLen, hash);
        return _fieldComplete(name);
    }

    private JsonToken _handleOddName(int ch) throws JacksonException {
        switch (ch) {
        case '#':
            if ((_formatReadFeatures & FEAT_MASK_ALLOW_YAML_COMMENTS) != 0) {
                return _finishHashComment(MINOR_PROPERTY_LEADING_WS);
            }
            break;
        case '/':
            return _startSlashComment(MINOR_PROPERTY_LEADING_WS);
        case '\'':
            if ((_formatReadFeatures & FEAT_MASK_ALLOW_SINGLE_QUOTES) != 0) {
                _nameLen = 0;
                _nameHash = _charSymbols.hashSeed();
                return _finishAposName(0, _nameHash);
            }
            break;
        case INT_RBRACKET:
            return _closeArrayScope();
        }
        if ((_formatReadFeatures & FEAT_MASK_ALLOW_UNQUOTED_NAMES) == 0) {
            _reportUnexpectedChar(ch, "was expecting double-quote to start field name");
        }
        final int[] codes = CharTypes.getInputCodeUtf8JsNames();
        if (ch < codes.length && codes[ch] != 0) {
            _reportUnexpectedChar(ch, "was expecting either valid name character (for unquoted name) or double-quote (for quoted) to start field name");
        }
        _nameLen = 0;
        _nameHash = _charSymbols.hashSeed();
        int hash = ((_nameHash) * CharsToNameCanonicalizer.HASH_MULT) + ch;
        _nameBuffer[0] = (char) ch;
        return _finishUnquotedName(1, hash);
    }

    private JsonToken _finishUnquotedName(int nameLen, int hash) throws JacksonException {
        final int[] codes = CharTypes.getInputCodeUtf8JsNames();

        while (true) {
            if (_inputPtr >= _inputEnd) {
                _nameLen = nameLen;
                _nameHash = hash;
                _minorState = MINOR_PROPERTY_UNQUOTED_NAME;
                return _updateTokenToNA();
            }
            int ch = getCharFromBuffer(_inputPtr);
            if (ch < codes.length && codes[ch] != 0) {
                break;
            }
            if (ch >= codes.length && !Character.isJavaIdentifierPart(ch)) {
                break;
            }
            ++_inputPtr;
            hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
            if (nameLen >= _nameBuffer.length) {
                _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
            }
            _nameBuffer[nameLen++] = (char) ch;
        }
        _streamReadConstraints.validateNameLength(nameLen);
        String name = _charSymbols.findSymbol(_nameBuffer, 0, nameLen, hash);
        return _fieldComplete(name);
    }

    private JsonToken _finishAposName(int nameLen, int hash) throws JacksonException {
        final int[] codes = _icLatin1;

        while (true) {
            if (_inputPtr >= _inputEnd) {
                _nameLen = nameLen;
                _nameHash = hash;
                _minorState = MINOR_PROPERTY_APOS_NAME;
                return _updateTokenToNA();
            }
            int ch = getNextCharFromBuffer();
            if (ch == INT_APOS) {
                break;
            }
            if (ch != '"' && ch <= 127 && codes[ch] != 0) {
                if (ch != INT_BACKSLASH) {
                    _throwUnquotedSpace(ch, "name");
                }
                ch = _decodeCharEscape();
                if (ch < 0) {
                    _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                    _minorStateAfterSplit = MINOR_PROPERTY_APOS_NAME;
                    _nameLen = nameLen;
                    _nameHash = hash;
                    return _updateTokenToNA();
                }
                // surrogate pair handling (same as _parseEscapedName)
                if (ch >= 0xD800 && ch <= 0xDBFF) {
                    int remaining = _inputEnd - _inputPtr;
                    if (remaining >= 6) {
                        if (getCharFromBuffer(_inputPtr) != INT_BACKSLASH) {
                            _reportError("Broken surrogate pair in property name: expected '\\' to start low surrogate");
                        }
                        ++_inputPtr;
                        int lo = _decodeFastCharEscape();
                        if (lo < 0xDC00 || lo > 0xDFFF) {
                            _reportError(String.format(
                                    "Broken surrogate pair in property name: expected low surrogate (DC00-DFFF), got %04X", lo));
                        }
                        ch = 0x10000 + ((ch - 0xD800) << 10) + (lo - 0xDC00);
                    } else {
                        _pendingSurrogateInName = ch;
                        _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                        _minorStateAfterSplit = MINOR_PROPERTY_APOS_NAME;
                        _nameLen = nameLen;
                        _nameHash = hash;
                        _quoted32 = 0;
                        _quotedDigits = -2;
                        return _updateTokenToNA();
                    }
                } else if (ch >= 0xDC00 && ch <= 0xDFFF) {
                    _reportUnexpectedLowSurrogate(ch);
                }
            }
            if (ch > 0xFFFF) {
                int sup = ch - 0x10000;
                char hi = (char) (0xD800 | (sup >> 10));
                char lo2 = (char) (0xDC00 | (sup & 0x3FF));
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + hi;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = hi;
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + lo2;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = lo2;
            } else {
                hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
                if (nameLen >= _nameBuffer.length) {
                    _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
                }
                _nameBuffer[nameLen++] = (char) ch;
            }
        }
        _streamReadConstraints.validateNameLength(nameLen);
        String name = _charSymbols.findSymbol(_nameBuffer, 0, nameLen, hash);
        return _fieldComplete(name);
    }

    protected final JsonToken _finishPropertyWithEscape() throws JacksonException {
        int ch;

        if (_pendingSurrogateInName != 0) {
            if (_quotedDigits == -2) {
                if (_inputPtr >= _inputEnd) {
                    return JsonToken.NOT_AVAILABLE;
                }
                int b = getNextCharFromBuffer();
                if (b != INT_BACKSLASH) {
                    _reportError("Broken surrogate pair in property name: expected '\\' to start low surrogate, got 0x"
                            + Integer.toHexString(b));
                }
                _quotedDigits = -1;
                _quoted32 = 0;
            }
            ch = _decodeSplitEscaped(_quoted32, _quotedDigits);
            if (ch < 0) {
                _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                return JsonToken.NOT_AVAILABLE;
            }
            ch = _decodeSurrogate(_pendingSurrogateInName, ch);
            _pendingSurrogateInName = 0;
        } else {
            ch = _decodeSplitEscaped(_quoted32, _quotedDigits);
            if (ch < 0) {
                _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                return JsonToken.NOT_AVAILABLE;
            }
            if (ch >= 0xD800 && ch <= 0xDBFF) {
                _pendingSurrogateInName = ch;
                _quoted32 = 0;
                _quotedDigits = -2;
                _minorState = MINOR_PROPERTY_NAME_ESCAPE;
                return _finishPropertyWithEscape();
            } else if (ch >= 0xDC00 && ch <= 0xDFFF) {
                _reportUnexpectedLowSurrogate(ch);
            }
        }

        int nameLen = _nameLen;
        int hash = _nameHash;
        if (ch > 0xFFFF) {
            int sup = ch - 0x10000;
            char hi = (char) (0xD800 | (sup >> 10));
            char lo = (char) (0xDC00 | (sup & 0x3FF));
            hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + hi;
            if (nameLen >= _nameBuffer.length) {
                _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
            }
            _nameBuffer[nameLen++] = hi;
            hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + lo;
            if (nameLen >= _nameBuffer.length) {
                _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
            }
            _nameBuffer[nameLen++] = lo;
        } else {
            hash = (hash * CharsToNameCanonicalizer.HASH_MULT) + ch;
            if (nameLen >= _nameBuffer.length) {
                _nameBuffer = Arrays.copyOf(_nameBuffer, _nameBuffer.length * 2);
            }
            _nameBuffer[nameLen++] = (char) ch;
        }
        _nameLen = nameLen;
        _nameHash = hash;

        if (_minorStateAfterSplit == MINOR_PROPERTY_APOS_NAME) {
            return _finishAposName(_nameLen, _nameHash);
        }
        return _parseEscapedName(_nameLen, _nameHash);
    }

    /*
    /**********************************************************************
    /* Second-level decoding, string decoding
    /**********************************************************************
     */

    protected JsonToken _startString() throws JacksonException {
        int ptr = _inputPtr;
        int outPtr = 0;
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        final int[] codes = _icLatin1;

        final int max = Math.min(_inputEnd, ptr + outBuf.length);
        while (ptr < max) {
            int c = getCharFromBuffer(ptr);
            if (c == INT_QUOTE) {
                _inputPtr = ptr + 1;
                _textBuffer.setCurrentLength(outPtr);
                return _valueComplete(JsonToken.VALUE_STRING);
            }
            // For chars > 127: valid content, no special handling needed (unlike UTF-8 bytes)
            if (c > 127 || codes[c] == 0) {
                ++ptr;
                outBuf[outPtr++] = (char) c;
                continue;
            }
            break;
        }
        _textBuffer.setCurrentLength(outPtr);
        _inputPtr = ptr;
        return _finishRegularString();
    }

    private final JsonToken _finishRegularString() throws JacksonException {
        char[] outBuf = _textBuffer.getBufferWithoutReset();
        int outPtr = _textBuffer.getCurrentSegmentSize();
        int ptr = _inputPtr;
        final int[] codes = _icLatin1;

        while (true) {
            if (ptr >= _inputEnd) {
                _inputPtr = ptr;
                _minorState = MINOR_VALUE_STRING;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.finishCurrentSegment();
                outPtr = 0;
            }
            final int max = Math.min(
                _inputEnd,
                InternalJacksonUtil.addOverflowSafe(ptr, outBuf.length - outPtr));
            while (ptr < max) {
                int c = getCharFromBuffer(ptr);
                if (c == INT_QUOTE) {
                    _inputPtr = ptr + 1;
                    _textBuffer.setCurrentLength(outPtr);
                    return _valueComplete(JsonToken.VALUE_STRING);
                }
                if (c > 127 || codes[c] == 0) {
                    ++ptr;
                    outBuf[outPtr++] = (char) c;
                    continue;
                }
                ++ptr;
                // Special char
                if (c == INT_BACKSLASH) {
                    _inputPtr = ptr;
                    int decoded = _decodeFastCharEscapeIfAvailable();
                    if (decoded < 0) {
                        // not enough input for escape; save state
                        _minorState = MINOR_VALUE_STRING_ESCAPE;
                        _minorStateAfterSplit = MINOR_VALUE_STRING;
                        _textBuffer.setCurrentLength(outPtr);
                        return _updateTokenToNA();
                    }
                    ptr = _inputPtr;
                    if (outPtr >= outBuf.length) {
                        outBuf = _textBuffer.finishCurrentSegment();
                        outPtr = 0;
                    }
                    outBuf[outPtr++] = (char) decoded;
                    continue;
                }
                if (c < INT_SPACE) {
                    _inputPtr = ptr;
                    _throwUnquotedSpace(c, "string value");
                }
                // Shouldn't reach here for valid chars
                outBuf[outPtr++] = (char) c;
            }
        }
    }

    protected JsonToken _startAposString() throws JacksonException {
        int ptr = _inputPtr;
        int outPtr = 0;
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        final int[] codes = _icLatin1;

        final int max = Math.min(_inputEnd, ptr + outBuf.length);
        while (ptr < max) {
            int c = getCharFromBuffer(ptr);
            if (c == INT_APOS) {
                _inputPtr = ptr + 1;
                _textBuffer.setCurrentLength(outPtr);
                return _valueComplete(JsonToken.VALUE_STRING);
            }
            if (c > 127 || (codes[c] == 0 && c != INT_QUOTE)) {
                ++ptr;
                outBuf[outPtr++] = (char) c;
                continue;
            }
            if (c == INT_QUOTE) {
                ++ptr;
                outBuf[outPtr++] = (char) c;
                continue;
            }
            break;
        }
        _textBuffer.setCurrentLength(outPtr);
        _inputPtr = ptr;
        return _finishAposString();
    }

    private final JsonToken _finishAposString() throws JacksonException {
        char[] outBuf = _textBuffer.getBufferWithoutReset();
        int outPtr = _textBuffer.getCurrentSegmentSize();
        int ptr = _inputPtr;
        final int[] codes = _icLatin1;

        while (true) {
            if (ptr >= _inputEnd) {
                _inputPtr = ptr;
                _minorState = MINOR_VALUE_APOS_STRING;
                _textBuffer.setCurrentLength(outPtr);
                return _updateTokenToNA();
            }
            if (outPtr >= outBuf.length) {
                outBuf = _textBuffer.finishCurrentSegment();
                outPtr = 0;
            }
            final int max = Math.min(
                _inputEnd,
                InternalJacksonUtil.addOverflowSafe(ptr, outBuf.length - outPtr));
            while (ptr < max) {
                int c = getCharFromBuffer(ptr);
                if (c == INT_APOS) {
                    _inputPtr = ptr + 1;
                    _textBuffer.setCurrentLength(outPtr);
                    return _valueComplete(JsonToken.VALUE_STRING);
                }
                if (c > 127 || (codes[c] == 0 && c != INT_QUOTE)) {
                    ++ptr;
                    outBuf[outPtr++] = (char) c;
                    continue;
                }
                if (c == INT_QUOTE) {
                    ++ptr;
                    outBuf[outPtr++] = (char) c;
                    continue;
                }
                ++ptr;
                if (c == INT_BACKSLASH) {
                    _inputPtr = ptr;
                    int decoded = _decodeFastCharEscapeIfAvailable();
                    if (decoded < 0) {
                        _minorState = MINOR_VALUE_STRING_ESCAPE;
                        _minorStateAfterSplit = MINOR_VALUE_APOS_STRING;
                        _textBuffer.setCurrentLength(outPtr);
                        return _updateTokenToNA();
                    }
                    ptr = _inputPtr;
                    if (outPtr >= outBuf.length) {
                        outBuf = _textBuffer.finishCurrentSegment();
                        outPtr = 0;
                    }
                    outBuf[outPtr++] = (char) decoded;
                    continue;
                }
                if (c < INT_SPACE) {
                    _inputPtr = ptr;
                    _throwUnquotedSpace(c, "string value");
                }
                outBuf[outPtr++] = (char) c;
            }
        }
    }

    /*
    /**********************************************************************
    /* Escape decoding
    /**********************************************************************
     */

    private final int _decodeCharEscape() throws JacksonException {
        if (_inputPtr >= _inputEnd) {
            return _decodeSplitEscaped(0, -1);
        }
        return _decodeFastCharEscape();
    }

    /**
     * Like _decodeFastCharEscape but returns -1 if we need more input
     * instead of calling the split version.
     */
    private final int _decodeFastCharEscapeIfAvailable() throws JacksonException {
        int left = _inputEnd - _inputPtr;
        if (left < 1) {
            return _decodeSplitEscaped(0, -1);
        }
        return _decodeFastCharEscape();
    }

    protected final int _decodeFastCharEscape() throws JacksonException {
        int c = getNextCharFromBuffer();
        switch (c) {
        case 'b': return '\b';
        case 't': return '\t';
        case 'n': return '\n';
        case 'f': return '\f';
        case 'r': return '\r';
        case '"':
        case '/':
        case '\\':
            return c;
        case 'u':
            break;
        default:
            return _handleUnrecognizedCharacterEscape((char) c);
        }
        // Unicode escape: need 4 hex digits
        if ((_inputEnd - _inputPtr) < 4) {
            return _decodeSplitEscaped(0, 0);
        }
        int ch = getNextCharFromBuffer();
        int digit = CharTypes.charToHex(ch);
        int result = digit;
        if (digit >= 0) {
            ch = getNextCharFromBuffer();
            digit = CharTypes.charToHex(ch);
            if (digit >= 0) {
                result = (result << 4) | digit;
                ch = getNextCharFromBuffer();
                digit = CharTypes.charToHex(ch);
                if (digit >= 0) {
                    result = (result << 4) | digit;
                    ch = getNextCharFromBuffer();
                    digit = CharTypes.charToHex(ch);
                    if (digit >= 0) {
                        return (result << 4) | digit;
                    }
                }
            }
        }
        _reportUnexpectedChar(ch, "expected a hex-digit for character escape sequence");
        return -1;
    }

    private int _decodeSplitEscaped(int value, int charsRead) throws JacksonException {
        if (_inputPtr >= _inputEnd) {
            _quoted32 = value;
            _quotedDigits = charsRead;
            return -1;
        }
        int c = getNextCharFromBuffer();
        if (charsRead == -1) {
            switch (c) {
            case 'b': return '\b';
            case 't': return '\t';
            case 'n': return '\n';
            case 'f': return '\f';
            case 'r': return '\r';
            case '"':
            case '/':
            case '\\':
                return c;
            case 'u':
                break;
            default:
                return _handleUnrecognizedCharacterEscape((char) c);
            }
            if (_inputPtr >= _inputEnd) {
                _quotedDigits = 0;
                _quoted32 = 0;
                return -1;
            }
            c = getNextCharFromBuffer();
            charsRead = 0;
        }
        while (true) {
            int digit = CharTypes.charToHex(c);
            if (digit < 0) {
                _reportUnexpectedChar(c, "expected a hex-digit for character escape sequence");
            }
            value = (value << 4) | digit;
            if (++charsRead == 4) {
                return value;
            }
            if (_inputPtr >= _inputEnd) {
                _quotedDigits = charsRead;
                _quoted32 = value;
                return -1;
            }
            c = getNextCharFromBuffer();
        }
    }

    /*
    /**********************************************************************
    /* Internal helpers
    /**********************************************************************
     */

    private final void _verifyRootSpace(int ch) throws JacksonException {
        ++_inputPtr;
        switch (ch) {
        case ' ':
        case '\t':
            return;
        case '\r':
            --_inputPtr;
            return;
        case '\n':
            ++_currInputRow;
            _currInputRowStart = _inputPtr;
            return;
        }
        _reportMissingRootWS(ch);
    }

    private void _setIntLength(final int len) throws StreamConstraintsException {
        _streamReadConstraints.validateIntegerLength(len);
        _intLength = len;
    }

    private void _setFractLength(final int len) throws StreamConstraintsException {
        _streamReadConstraints.validateFPLength(_intLength + len);
        _fractLength = len;
    }

    private void _setExpLength(final int len) throws StreamConstraintsException {
        _streamReadConstraints.validateFPLength(_intLength + _fractLength + len);
        _expLength = len;
    }
}
