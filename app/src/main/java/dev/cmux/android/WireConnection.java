package dev.cmux.android;

import java.io.InputStream;
import java.io.OutputStream;

interface WireConnection extends AutoCloseable {
    InputStream input();
    OutputStream output();
}
