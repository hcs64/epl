package epl;

import io.socket.*;
import org.json.*;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Queue;
import java.util.HashMap;
import java.util.LinkedList;
import java.io.IOException;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.Socket;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;

// class for talking to an Etherpad Lite server about a particular pad

public class Pad {
    // client states
    enum ClientConnectState {
        NO_CONNECTION,
        GETTING_SESSION_TOKEN,
        GOT_SESSION_TOKEN,
        CONNECTING,
        SENT_CLIENT_READY,
        GOT_VARS,
        NORMAL, // fully connected, our client has the initial text
    }
    private volatile ClientConnectState client_connect_state;

    // following the documentation (Etherpad and EasySync Technical Manual):
    // []A: server_text (the last known shared revision)
    //   X: sent_changes (changes we have make locally and transmitted that have not been ack'd)
    //   Y: pending_changes (changes we have made locally and not transmitted yet)
    //   V: client_text (our local version)
    private String server_text;
    private long server_rev;

    private String client_text;
    private long client_rev;

    private Changeset sent_changes;
    private Changeset pending_changes;

    // queue of unprocessed messages
    private Queue<JSONObject> collabroom_messages;

    // we maintain the positions of markers which get jostled around by
    // remote and local updates
    ArrayList<Marker> markers;

    private volatile JSONObject client_vars = null; // initial state from the server

    private URL url;
    private String session_token;
    private String token;
    private String client_id;
    private String pad_id;

    private SocketIO socket = null;

    public Pad(
        URL url,
        String client_id,   // can be ""
        String token,       // can be null
        String pad_id,      // must be set
        String session_token // can be null
        ) {
        this.url = url;

        if (token == null) {
            token = "t." + randomString();
        }

        this.token = token;
        this.client_id = client_id;
        this.pad_id = pad_id;
        this.session_token = session_token;

        client_connect_state = ClientConnectState.NO_CONNECTION;

        server_text = null;
        server_rev = -1;
        client_text = null;
        client_rev = -1;

        sent_changes = null;
        pending_changes = null;

        collabroom_messages = new LinkedList<JSONObject> ();

        markers = new ArrayList<Marker> ();
    }

    // adapted from Etherpad Lite's JS
    public static String randomString() {
        final String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        final int string_length = 20;
        StringBuilder randomstring = new StringBuilder(string_length);

        for (int i = 0; i < string_length; i++)
        {
            int rnum = (int) Math.floor(Math.random() * chars.length());
            randomstring.append(chars.charAt(rnum));
        }
        return randomstring.toString();
    }


    public static String getSessionToken(URL url) throws IOException, MalformedURLException, PadException {
        // a really dumb HTTP client so Sun's HttpURLConnection doesn't eat the Set-Cookie
        final String set_cookie = "Set-Cookie: ";
        Socket http_socket = new Socket(url.getHost(), url.getPort());

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

    private void handleIncomingMessage(JSONObject json) throws PadException {
        String type;
        
        try {
            type = json.getString("type");
        } catch (JSONException e) {
            throw new PadException("couldn't get type of incoming message", e);
        }

        if ("CLIENT_VARS".equals(type)) {
            setClientVars(json);
        } else if ("COLLABROOM".equals(type)) {
            queueCollabRoom(json);
        } else {
            // unhandled message type
            System.out.print("unknown message type: " + type + ", keys: ");
            for (Iterator i = json.keys(); i.hasNext(); ) {
                String k = (String) i.next();
                System.out.print("'"+k+"' ");
            }
            System.out.println();
        }
    }


    public synchronized void connect() throws IOException, MalformedURLException, PadException {
        if (client_connect_state != ClientConnectState.NO_CONNECTION) {
            throw new PadException("can't connect again");
        }

        socket = new SocketIO(url);

        client_connect_state = ClientConnectState.GETTING_SESSION_TOKEN;
        if (session_token == null) {
            session_token = getSessionToken(url);
        }
        client_connect_state = ClientConnectState.GOT_SESSION_TOKEN;
        socket.addHeader("Cookie", session_token);

        socket.connect(new IOCallback() {
            @Override
            public void onMessage(JSONObject json, IOAcknowledge ack) {
                try {
                    handleIncomingMessage(json);
                } catch (PadException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(String data, IOAcknowledge ack) {
                System.out.println("Server sent string: " + data);
            }

            @Override
            public void onError(SocketIOException socketIOException) {
                System.out.println("an Error occurred");
                socketIOException.printStackTrace();
            }

            @Override
            public void onDisconnect() {
                System.out.println("Connection terminated.");
                markDisconnected();
            }

            @Override
            public void onConnect() {
                System.out.println("Connection established.");

                try {
                    sendClientReady();
                } catch (PadException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void on(String event, IOAcknowledge ack, Object... args) {
                System.out.println("Server triggered event '" + event + "'");
            }
        });

        client_connect_state = ClientConnectState.CONNECTING;
    }


    private synchronized void sendClientReady() throws PadException {
        if (client_connect_state != ClientConnectState.CONNECTING) {
            throw new PadException("sendClientReady in unexpected state "+client_connect_state);
        }

        HashMap client_ready_req = new HashMap<String, Object>() {{
            put("component", "pad");
            put("type", "CLIENT_READY");
            put("padId", pad_id);
            put("sessionID", null);
            put("token", token);
            put("password", null);
            put("protocolVersion", 2);
        }};

        JSONObject client_ready_json = new JSONObject(client_ready_req);

        socket.send(client_ready_json);

        client_connect_state = ClientConnectState.SENT_CLIENT_READY;
    }

    private synchronized void markDisconnected() {
        client_connect_state = ClientConnectState.NO_CONNECTION;
        notifyAll();
    }

    public void assertConnectionOK() throws PadException {
        ClientConnectState ccs = client_connect_state;

        if (ccs != ClientConnectState.NORMAL) {
            throw new PadException("connection is in bad state " + ccs);
        }
    }

    public boolean isConnecting() {
        switch (client_connect_state) {
        case NO_CONNECTION:
            return false;
        case GETTING_SESSION_TOKEN:
        case GOT_SESSION_TOKEN:
        case CONNECTING:
        case SENT_CLIENT_READY:
            return true;
        case GOT_VARS:
        case NORMAL:
            // these are considered "connected"
            return false;
        }

        return false;
    }

    public boolean isConnected() {
        switch (client_connect_state) {
        case NO_CONNECTION:
        case GETTING_SESSION_TOKEN:
        case GOT_SESSION_TOKEN:
        case CONNECTING:
        case SENT_CLIENT_READY:
            return false;
        case GOT_VARS:
        case NORMAL:
            return true;
        }

        return false;
    }

    public boolean isDisconnected() {
        switch (client_connect_state) {
        case NO_CONNECTION:
            return true;
        case GETTING_SESSION_TOKEN:
        case GOT_SESSION_TOKEN:
        case CONNECTING:
        case SENT_CLIENT_READY:
        case GOT_VARS:
        case NORMAL:
            return false;
        }

        return false;
    }

    private synchronized void setClientVars(JSONObject json) throws PadException {
        if (client_connect_state != ClientConnectState.SENT_CLIENT_READY) {
            throw new PadException("setClientVars in unexpected state "+client_connect_state);
        }

        try {
            client_vars = json.getJSONObject("data");
            JSONObject collab_client_vars = client_vars.getJSONObject("collab_client_vars");

            server_text = collab_client_vars.getJSONObject("initialAttributedText").getString("text");
            server_rev = collab_client_vars.getLong("rev");

            client_text = server_text;
            client_rev = server_rev;

            pending_changes = sent_changes = Changeset.identity(server_text.length());

            client_connect_state = ClientConnectState.GOT_VARS;
        } catch (JSONException e) {
            throw new PadException("exception getting CLIENT_VARS data");
        }
    }

    private synchronized void queueCollabRoom(JSONObject json) throws PadException {
        if (!isConnected()) {
            throw new PadException("queueCollabRoom in unexpected state "+client_connect_state);
        }

        collabroom_messages.add(json);
    }

    // most of the action is in here
    // returns true if there is something new for the client
    public synchronized boolean update(boolean is_sending, boolean is_receiving) throws PadException {
        boolean has_new = false;

        if (!isConnected()) {
            return false;
        }

        if (client_connect_state == ClientConnectState.GOT_VARS) {
            has_new = true;
            client_connect_state = ClientConnectState.NORMAL;
        }

        if (is_receiving) {
            while (!collabroom_messages.isEmpty()) {
                JSONObject json = collabroom_messages.poll();
                JSONObject data;
                String collab_type;

                try {
                    data = json.getJSONObject("data");
                    collab_type = data.getString("type");
                } catch (JSONException e) {
                    throw new PadException("error getting COLLABROOM metadata", e);
                }

                if (handleCollabRoom(data, collab_type)) {
                    has_new = true;
                }
            }
        }

        if (is_sending) {
            commitChanges();
        }

        return has_new;
    }

    // The main accessor, get a completely coherent snapshot.
    // It is up to the client to not make any changes (by a call to
    // update() or any of the change methods) if the client_text
    // herein is to remain accurate
    public synchronized TextState getState() {
        Marker[] client_markers = new Marker[markers.size()];
        
        client_markers = markers.toArray(client_markers);
        return new TextState(server_text, server_rev, client_text, client_rev, client_markers);
    }

    // only call when synchronized
    // return true if anything was actually sent
    private boolean commitChanges() throws PadException {
        if (sent_changes.isIdentity() && !pending_changes.isIdentity()) {
            JSONObject user_changes;

            try {
                user_changes = new JSONObject() {{
                    put("component", "pad");
                    put("type", "COLLABROOM");
                    put("data", new JSONObject() {{
                        put("type", "USER_CHANGES");
                        put("baseRev", server_rev);
                        put("changeset", pending_changes);

                        // dummy empty attribute pool
                        put("apool", new JSONObject() {{
                            put("numToAttrib", new Object[] {});
                            put("nextNum",0);
                        }});
                    }});
                }};
            } catch (JSONException e) {
                throw new PadException("failed building USER_CHANGES JSON", e);
            }

            socket.send(null, user_changes);

            sent_changes = pending_changes;
            pending_changes = Changeset.identity(sent_changes.newLen);

            return true;
        }

        return false;
    }

    // only call when synchronized
    // returns true if there's something new for the client
    private boolean handleCollabRoom(JSONObject data, String collab_type) throws PadException {
        boolean has_new = false;

        if ("NEW_CHANGES".equals(collab_type)) {
            String changeset_str;
            try {
                changeset_str = data.getString("changeset");
            } catch (JSONException e) {
                throw new PadException("error getting data from NEW_CHANGES", e);
            }

            String new_text;
            long new_time;
            long new_rev;
            long time_delta = 0;
            String cs_str;

            try {
                // This is the heart of the protocol, notation here is from
                // the technical manual and Etherpad Lite's changesettracker.js

                try {
                    cs_str = data.getString("changeset");

                    new_rev = data.getLong("newRev");
                    new_time = data.getLong("currentTime");
                    if (!data.isNull("timeDelta")) {
                        time_delta = data.getLong("timeDelta");
                    }
                } catch (JSONException e) {
                    throw new PadException("error updating from NEW_CHANGES", e);
                }

                // A' = AB
                Changeset B = new Changeset(cs_str);
                new_text = B.applyToText(server_text);

                // X' = f(B, X)
                // var c2 = c
                Changeset fXB = B;
                Changeset X_prime = sent_changes;

                // if (submittedChangeset) 
                if (!sent_changes.isIdentity()) {
                    // var oldSubmittedChangeset = submittedChangeset;
                    // submittedChangeset = Changeset.follow(c, oldSubmittedChangeset, false, apool);
                    X_prime = Changeset.follow(B, sent_changes, false);
                    // c2 = Changeset.follow(oldSubmittedChangeset, c, true, apool);
                    fXB = Changeset.follow(sent_changes, B, true);
                }


                // Y' = f(f(X, B), Y)
                // var preferInsertingAfterUserChanges = true;
                // var oldUserChangeset = userChangeset;
                // userChangeset = Changeset.follow(c2, oldUserChangeset, preferInsertingAfterUserChanges, apool);
                Changeset Y_prime = Changeset.follow(fXB, pending_changes, true);

                // D = f(Y, f(X, B))
                // var postChange = Changeset.follow(oldUserChangeset, c2, !preferInsertingAfterUserChanges, apool);
                Changeset D = Changeset.follow(pending_changes, fXB, false);

                sent_changes = X_prime;
                pending_changes = Y_prime;

                server_text = new_text;
                server_rev = new_rev;

                if (sent_changes.isIdentity() && pending_changes.isIdentity()) {
                    client_rev = new_rev;
                } else {
                    client_rev = -1;
                }

                if (!D.isIdentity()) {
                    client_text = D.applyToText(client_text);
                    translateMarkers(D);

                    has_new = true;
                }

                // DEBUG: check out that all these follows seem to work as intended
                String server_would_see = pending_changes.applyToText(server_text);
                if (!server_would_see.equals(client_text)) {
                    throw new PadException("out of sync, server would see\n'" + server_would_see + "'\nclient sees\n'" + client_text + "'\n");
                }
            } catch (ChangesetException e) {
                throw new PadException("NEW_CHANGES broke", e);
            }

        } else if ("ACCEPT_COMMIT".equals(collab_type)) {

            String new_text;
            long new_rev;

            try {
                new_rev = data.getLong("newRev");
            } catch (JSONException e) {
                throw new PadException("failed getting ACCEPT_COMMIT's newRev", e);
            }

            try {
                new_text = sent_changes.applyToText(server_text);

            } catch (ChangesetException e) {
                throw new PadException("failed applying confirmed changes on ACCEPT_COMMIT", e);
            }

            server_text = new_text;
            server_rev = new_rev;

            if (pending_changes.isIdentity()) {
                client_rev = new_rev;
                // assert client_text.equals( server_text? )
            } else {
                client_rev = -1;
            }

            sent_changes = Changeset.identity(server_text.length());

            // the acceptance should not introduce any new data to the client
            //has_new = true;
        } else if ("USER_NEWINFO".equals(collab_type)) {
            // ignore this for now

        } else if ("USER_LEAVE".equals(collab_type)) {
            // ignore this for now

        } else {
            System.out.println("unsupported COLLABROOM message type = " + collab_type);
        }

        return has_new;
    }

    // ********** Marker manipulation
    // 
    public int registerMarker(int pos, boolean before, boolean valid) throws PadException {
        synchronized(markers) {
            markers.add(new Marker(pos, before, valid));
            return markers.size()-1;
        }
    }

    public void reregisterMarker(int idx, int pos, boolean before, boolean valid) throws PadException {
        synchronized(markers) {
            markers.set(idx, new Marker(pos, before, valid));
        }
    }

    private void translateMarkers(Changeset cs) {
        synchronized(markers) {
            for (int i = 0; i < markers.size(); i++) {
                markers.set(i, cs.translateMarker(markers.get(i)));
            }
        }
    }

    // ********* Change interface

    public synchronized void makeChange(Changeset changeset) throws ChangesetException {
        makeChangeInternal(changeset);
    }

    public synchronized void makeChange(int pos, int removing, String new_s) throws PadException {
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, pos, removing, new_s));
        } catch (ChangesetException e) {
            throw new PadException("error assembling or applying changeset", e);
        }
    }

    // set "follow" true to have the marker move to the end of the inserted text
    public void insertAtMarker(int marker_idx, String new_s, boolean follow) throws PadException {
        Marker marker = markers.get(marker_idx);

        int marker_old_pos = marker.pos;
        int marker_offset = 0;

        if (!marker.before) {
            marker_offset = 1;
        }
        
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, marker_old_pos + marker_offset, 0, new_s));
        } catch (ChangesetException e) {
            throw new PadException("", e);
        }

        int marker_new_pos;

        if (follow) {
            // A[BC => A123[BC
            // AB]C => AB123[C

            marker_new_pos = marker_old_pos + new_s.length();
        } else {
            // A[BC => A[123BC
            // AB]C => AB]123C
            marker_new_pos = marker_old_pos;
        }

        synchronized(markers) {
            markers.set(marker_idx, new Marker(marker_new_pos, marker.before, true));
        }
    }

    public void replaceBetweenMarkers(int start_marker_idx, int end_marker_idx, String new_s) throws PadException {
        Marker start_marker = markers.get(start_marker_idx);
        Marker end_marker = markers.get(end_marker_idx);

        int start_pos = start_marker.pos;
        int start_pos_offset = 0;
        int end_pos = end_marker.pos;
        int end_pos_offset = 0;

        if (start_marker.before) {
            // A[BC...
            // replace includes this character, no change
        } else {
            // AB]C...
            // replace excludes this character, inc
            start_pos_offset = 1;
        }

        if (end_marker.before) {
            // ...A[BC
            // replace excludes this character, no change (end is noninclusive)
        } else {
            // ...AB]C
            // replace includes this character, inc
            end_pos_offset = 1;
        }

        if (end_pos + end_pos_offset < start_pos + start_pos_offset) {
            throw new PadException("marked range ends before it begins");
        }

        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, start_pos + start_pos_offset, end_pos + end_pos_offset - (start_pos + start_pos_offset), new_s));
        } catch (ChangesetException e) {
            throw new PadException("", e);
        }

        // update markers (removing text generally invalidates markers)
        synchronized(markers) {
            markers.set(start_marker_idx, new Marker(start_pos - start_pos_offset, start_marker.before, true));
            markers.set(end_marker_idx, new Marker(start_pos + new_s.length() - end_pos_offset, end_marker.before, true));
        }
    }

    // returns new marker index i, i and i+1 are markers for the appended text (i is a 'before' marker, i+1 is after)
    public int prependTextAndMark(String new_s) throws PadException {
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, 0, 0, new_s));
        } catch (ChangesetException e) {
            throw new PadException("error assembling or applying prepend changeset", e);
        }

        synchronized (markers) {
            markers.add(new Marker(0, true, true));
            markers.add(new Marker(new_s.length()-1, false, true));
            return markers.size()-2;
        }
    }

    public void appendText(String new_s) throws PadException {
        int pos = client_text.length()-1;
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, pos, 0, new_s));
        } catch (ChangesetException e) {
            throw new PadException("error assembling or applying append changeset", e);
        }
    }

    // returns new marker index i, i and i+1 are markers for the appended text (i is a 'before' marker, i+1 is after)
    public int appendTextAndMark(String new_s) throws PadException {
        int pos = client_text.length()-1;
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, pos, 0, new_s));
        } catch (ChangesetException e) {
            throw new PadException("error assembling or applying append changeset", e);
        }

        synchronized (markers) {
            markers.add(new Marker(pos, true, true));
            markers.add(new Marker(pos+new_s.length()-1, false, true));
            return markers.size()-2;
        }
    }

    // ********* private changeset application

    // only call when synchronized!
    private void makeChangeInternal(Changeset changeset) throws ChangesetException {
        pending_changes = Changeset.compose(pending_changes, changeset);

        client_text = changeset.applyToText(client_text);
        translateMarkers(changeset);
    }
}