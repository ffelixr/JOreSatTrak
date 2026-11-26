/*
 * =====================================================================
 *   This file is part of JSatTrak.
 *
 *   Copyright 2007-2013 Shawn E. Gano
 *   
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *   
 *       http://www.apache.org/licenses/LICENSE-2.0
 *   
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * =====================================================================
 * 
 * Modified version of: http://www.artima.com/forums/flat.jsp?forum=1&thread=148613
 * Modifications by: Shawn Gano
 */

package jsattrak.utilities;

import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class Console extends FilterOutputStream
{

    private static final String LOG_PREFIX = "[JOreSatTrak] ";
    private static ArrayList<ConsoleListener> registeredListeners = new ArrayList<ConsoleListener>();
    public static final PrintStream originalOut = System.out;
    public static final PrintStream originalErr = System.err;

    static
    {
        // Tee stdout and stderr so logs appear in both the GUI console and terminal
        PrintStream printStream =
                new PrintStream(
                new Console(new ByteArrayOutputStream()), true, StandardCharsets.UTF_8);
        System.setOut(printStream);
        System.setErr(printStream);
    }

    /**
     * Ensures stdout/stderr redirection is installed before early startup logging.
     */
    public static void install()
    {
        // Static initializer performs the installation once on class load.
    }

    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

    public Console(OutputStream out)
    {
        super(out);
    }

    /* Override Ancestor method */
    public void write(byte b[]) throws IOException
    {
        write(b, 0, b.length);
    }

    /* Override Ancestor method */
    public void write(byte b[], int off, int len) throws IOException
    {
        synchronized (lineBuffer)
        {
            for (int i = 0; i < len; i++)
            {
                processByte(b[off + i]);
            }
        }
    }

    /* Override Ancestor method */
    public void write(int b) throws IOException
    {
        synchronized (lineBuffer)
        {
            processByte(b);
        }
    }

    @Override
    public void flush() throws IOException
    {
        originalOut.flush();
    }

    @Override
    public void close() throws IOException
    {
        synchronized (lineBuffer)
        {
            if (lineBuffer.size() > 0)
            {
                emitBufferedLine();
            }
        }
        originalOut.flush();
    }

    private void processByte(int b) throws IOException
    {
        lineBuffer.write(b);
        if (b == '\n')
        {
            emitBufferedLine();
        }
    }

    private void emitBufferedLine() throws IOException
    {
        String rawLine = lineBuffer.toString(StandardCharsets.UTF_8);
        lineBuffer.reset();
        String formatted = formatWithPrefix(rawLine);
        originalOut.write(formatted.getBytes(StandardCharsets.UTF_8));
        logMessage(formatted);
    }

    static String formatWithPrefix(String line)
    {
        if (line == null || line.isEmpty())
        {
            return "";
        }
        String body = line;
        String ending = "";
        if (body.endsWith("\r\n"))
        {
            body = body.substring(0, body.length() - 2);
            ending = "\r\n";
        }
        else if (body.endsWith("\n"))
        {
            body = body.substring(0, body.length() - 1);
            ending = "\n";
        }
        if (body.isBlank() || body.startsWith("[JOreSatTrak]"))
        {
            return body + ending;
        }
        return LOG_PREFIX + body + ending;
    }

    public static void registerOutputListener(ConsoleListener listener)
    {
        // we don't register null listeners
        if (listener != null)
        {
            registeredListeners.add(listener);
        }
    }

    public static void removeOutputListener(ConsoleListener listener)
    {
        if (listener != null)
        {
            registeredListeners.remove(listener);
        }
    }

    private static void logMessage(String message)
    {
        // Log output to each listener
        int count = registeredListeners.size();
        for (int i = 0; i < count; i++)
        {
            ((ConsoleListener) registeredListeners.get(i)).logMessage(message);
        }
    }
}
