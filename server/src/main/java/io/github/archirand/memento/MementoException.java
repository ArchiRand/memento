package io.github.archirand.memento;

/**
 * An error the agent can fix by itself; its message goes into the tool result as is.
 */
final class MementoException extends RuntimeException
{
    MementoException(String message)
    {
        super(message);
    }
}
