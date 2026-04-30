package tools.jackson.core.unittest.testutil;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.async.CharArrayFeeder;
import tools.jackson.core.exc.StreamReadException;

/**
 * Helper class used with async char-array parser
 */
public class AsyncReaderWrapperForCharArray extends AsyncReaderWrapper
{
    private final char[] _doc;
    private final int _charsPerFeed;
    private final int _padding;

    private int _offset;
    private int _end;

    public AsyncReaderWrapperForCharArray(JsonParser sr, int charsPerCall,
            char[] doc, int padding)
    {
        super(sr);
        _charsPerFeed = charsPerCall;
        _doc = doc;
        _offset = 0;
        _end = doc.length;
        _padding = padding;
    }

    @Override
    public JsonToken nextToken()
    {
        JsonToken token;

        while ((token = _streamReader.nextToken()) == JsonToken.NOT_AVAILABLE) {
            CharArrayFeeder feeder = (CharArrayFeeder) _streamReader.nonBlockingInputFeeder();
            if (!feeder.needMoreInput()) {
                throw new StreamReadException(null, "Got NOT_AVAILABLE, could not feed more input");
            }
            int amount = Math.min(_charsPerFeed, _end - _offset);
            if (amount < 1) { // end-of-input?
                feeder.endOfInput();
            } else {
                if (_padding == 0) {
                    feeder.feedInput(_doc, _offset, _offset + amount);
                } else {
                    char[] tmp = new char[amount + _padding + _padding];
                    System.arraycopy(_doc, _offset, tmp, _padding, amount);
                    feeder.feedInput(tmp, _padding, _padding + amount);
                }
                _offset += amount;
            }
        }
        return token;
    }
}
