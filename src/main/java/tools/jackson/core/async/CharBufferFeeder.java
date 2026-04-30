package tools.jackson.core.async;

import java.nio.CharBuffer;

import tools.jackson.core.JacksonException;

/**
 * {@link NonBlockingInputFeeder} implementation used when feeding data
 * as {@link CharBuffer} contents.
 */
public interface CharBufferFeeder extends NonBlockingInputFeeder
{
     /**
      * Method that can be called to feed more data, if (and only if)
      * {@link NonBlockingInputFeeder#needMoreInput} returns true.
      *
      * @param buffer Buffer that contains additional input to read
      *
      * @throws JacksonException if the state is such that this method should not be called
      *   (has not yet consumed existing input data, or has been marked as closed)
      */
     public void feedInput(CharBuffer buffer) throws JacksonException;
}
