package io.github.tommasobenatti.ncd;

/** An invalid native custom-click envelope; never fall through to a different execution path. */
final class MalformedClickException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    MalformedClickException(String message) { super(message); }
}
