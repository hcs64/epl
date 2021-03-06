package epl;

import io.socket.*;
import org.json.*;
import java.net.URL;
import java.net.URLEncoder;
import java.net.MalformedURLException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.util.logging.Logger;
import java.util.logging.Handler;

// handles the lifetime of a single connection to the Etherpad server

public class PadConnection {
    private volatile ClientConnectState client_connect_state;
    private SocketIO socket;
    private Pad pad;

    public PadConnection(Pad pad) {
        this.pad = pad;
        client_connect_state = ClientConnectState.NO_CONNECTION;

        socket = null;
    }

    // client states
    enum ClientConnectState {
        NO_CONNECTION,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private synchronized void markDisconnected() {
        client_connect_state = ClientConnectState.DISCONNECTED;
        notifyAll();
    }

    public void assertConnectionOK() throws PadException {
        ClientConnectState ccs = client_connect_state;

        if (ccs != ClientConnectState.CONNECTED) {
            throw new PadException("connection is in bad state " + ccs);
        }
    }

    public boolean isConnecting() {
        switch (client_connect_state) {
        case NO_CONNECTION:
            return false;
        case CONNECTING:
            return true;
        case CONNECTED:
        case DISCONNECTED:
            return false;
        }

        return false;
    }

    public boolean isConnected() {
        switch (client_connect_state) {
        case NO_CONNECTION:
        case CONNECTING:
            return false;
        case CONNECTED:
            return true;
        case DISCONNECTED:
            return false;
        }

        return false;
    }

    static Logger socket_logger = null;

    public void connect(URL url, String session_token) throws IOException, PadException {
        connect(url, session_token, null);
    }

    public void connect(URL url, String session_token, Handler log_handler) throws IOException, PadException {
        if (client_connect_state != ClientConnectState.NO_CONNECTION) {
            throw new PadException("can only connect once");
        }

        socket = new SocketIO(url);

        if (socket_logger == null)
        {
            socket_logger = SocketIO.getConnectionLogger();
            socket_logger.setUseParentHandlers(false);
        }

        if (log_handler != null) {
            socket_logger.addHandler(log_handler);
        }

        socket.addHeader("Cookie", session_token);

        socket.connect(new IOCallback() {
            @Override
            public void onMessage(JSONObject json, IOAcknowledge ack) {
                if (isConnected()) {
                    pad.onMessage(json);
                } else {
                    System.out.println("Ignoring JSON sent while not connected");
                }
            }

            @Override
            public void onMessage(String data, IOAcknowledge ack) {
                System.out.println("Server sent string: " + data);
            }

            @Override
            public void onError(SocketIOException socketIOException) {
                System.out.println("an Error occurred");

                boolean was_connecting = isConnecting();

                markDisconnected();
                pad.onDisconnect(was_connecting);
            }

            @Override
            public void onDisconnect() {
                System.out.println("Connection terminated.");

                boolean was_connecting = isConnecting();

                markDisconnected();
                pad.onDisconnect(was_connecting);
            }

            @Override
            public void onConnect() {
                if (client_connect_state != ClientConnectState.CONNECTING) {
                    System.out.println("Ignoring onConnect while not connecting!");
                } else {
                    System.out.println("Connection established.");

                    client_connect_state = ClientConnectState.CONNECTED;
                    pad.onConnect();
                }
            }

            @Override
            public void on(String event, IOAcknowledge ack, Object... args) {
                System.out.println("Server triggered event '" + event + "'");
            }
        });

        client_connect_state = ClientConnectState.CONNECTING;

    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }

        client_connect_state = ClientConnectState.DISCONNECTED;
    }

    public void send(JSONObject json) {
        socket.send(json);
    }

    public static String getSessionToken(URL url) throws IOException, MalformedURLException, PadException {
        // a really dumb HTTP client so Sun's HttpURLConnection doesn't eat the Set-Cookie
        final String set_cookie = "Set-Cookie: ";
        int port = url.getPort();
        if (port == -1) {
            port = url.getDefaultPort();
            if (port == -1) {
                port = 80;
            }
        }
        Socket http_socket = new Socket(url.getHost(), port);

        OutputStream http_out = http_socket.getOutputStream();
        byte[] http_req = "GET / HTTP/1.0\r\n\r\n".getBytes();
        http_out.write(http_req);
        http_out.flush();

        InputStream http_in_stream = http_socket.getInputStream();
        InputStreamReader http_in_reader = new InputStreamReader(http_in_stream);
        BufferedReader http_bufreader = new BufferedReader(http_in_reader);
        String line;

        while ((line = http_bufreader.readLine()) != null) {
            if (line.startsWith(set_cookie)) {
                String[] entries = line.substring(set_cookie.length()).split("; ");
                for (String entry : entries) {
                    String[] keyval = entry.split("=");
                    if (keyval.length == 2 && keyval[0].equals("express_sid")) {
                        return entry;
                    }
                }
            }
        }

        throw new PadException("no express_sid found");
    }

    public static void sendClientError(URL url, String session_token, String err_msg) {
        try {
            HttpURLConnection con = (HttpURLConnection)url.openConnection();
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            con.setRequestProperty("Cookie", "express_sid="+session_token);
            OutputStream os = con.getOutputStream();

            os.write("errorInfo=".getBytes("UTF-8"));
            os.write(URLEncoder.encode(err_msg, "UTF-8").getBytes("UTF-8"));
            os.close();

            InputStream is = con.getInputStream();
            byte[] buffer = new byte[1024];
            while(is.read(buffer) > 0) {
            }
            is.close();

        } catch (IOException e) {
            e.printStackTrace();
            }
    }

}
