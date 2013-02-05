package epl;

import org.json.*;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Queue;
import java.util.HashMap;
import java.util.LinkedList;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.*;

// class for talking to an Etherpad Lite server about a particular pad
// I'm attempting to be centered around the client text state, ideally
// it should be able to run fine offline

public class Pad {

    class PadLogHandler extends Handler {
        XMLFormatter formatter = null;
        URL err_url;
        String sid;
        public PadLogHandler(URL err_url, String sid) {
            this.err_url = err_url;
            this.sid = sid;
            formatter = new XMLFormatter();
        }
        public void close() { formatter = null; }
        public void flush() { } // no buffering
        public void publish(LogRecord r) {
            if (r != null && connection != null) {
                connection.sendClientError(err_url, sid, formatter.format(r));
            }
        }
    }

    static final Pattern cursor_chat_regex = Pattern.compile("!cursor!(\\d+)(-(\\d+))?");

    // following the documentation (Etherpad and EasySync Technical Manual):
    // []A: server_text (the last known shared revision)
    //   X: sent_changes (changes we have make locally and transmitted that have not been ack'd)
    //   Y: pending_changes (changes we have made locally and not transmitted yet)
    //   V: client_text (our local version)
    private String server_text;
    private long server_rev;
    private long server_time_offset;

    private String client_text;
    private long client_rev;

    private boolean read_only;
    private String read_only_pad_id;

    private Changeset sent_changes;
    private Changeset pending_changes;

    // queue of unprocessed messages
    private Queue<JSONObject> collabroom_messages;

    // we maintain the positions of markers which get jostled around by
    // remote and local updates
    ArrayList<Marker> markers;

    HashMap<String, Avatar> user_avatars;

    private volatile JSONObject client_vars; // initial state from the server
    private boolean client_vars_new;

    private URL url;
    private String session_token;
    private String token;
    private String client_id;
    private String user_id;
    private String pad_id;

    private Logger logger;

    private PadConnection connection;
    private boolean failed_connecting = false;

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
        this.user_id = null;
        this.pad_id = pad_id;
        this.session_token = session_token;

        server_text = "\n";
        server_rev = 0;
        server_time_offset = 0;
        client_text = "\n";
        client_rev = 0;

        client_vars = null;
        client_vars_new = false;

        sent_changes = null;
        pending_changes = null;
        logger = null;

        read_only = true;
        read_only_pad_id = null;

        collabroom_messages = new LinkedList<JSONObject> ();

        markers = new ArrayList<Marker> ();

        user_avatars = new HashMap<String, Avatar> ();
    }

    // shorthand constructor for an anonymous connection
    public Pad( URL url, String pad_id ) {
        this(url, "", null, pad_id, null);
    }

    public synchronized void connect() throws IOException, PadException {
        if (failed_connecting) {
            throw new PadException("already tried and failed to connect");
        }

        if (session_token == null) {
            session_token = PadConnection.getSessionToken(url);
        }

        if (connection != null) {
            throw new PadException("already have a connection!");
        }

        connection = new PadConnection(this);

        URL err_url;

        {
            int port = url.getPort();
            if (port == -1) {
                port = url.getDefaultPort();
            }
            err_url = new URL(url.getProtocol(), url.getHost(), port, "/jserror");
        }
        MemoryHandler log_handler = new MemoryHandler(new PadLogHandler(err_url, session_token), 1000, Level.WARNING);
        logger = Logger.getAnonymousLogger();
        logger.addHandler(log_handler);
        connection.connect(url, session_token, log_handler);
    }

    public synchronized void logThrowableToServer(Throwable e) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(os, true);
        e.printStackTrace(ps);

        logger.severe(os.toString());
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

    void onConnect() {
        try {
            sendClientReady();
        } catch (PadException e) {
            e.printStackTrace();
        }
    }

    void onDisconnect(boolean was_connecting) {
        System.err.println("onDisconnect("+was_connecting+")");
        connection = null;
        client_vars = null;
        client_vars_new = false;
        session_token = null;

        if (was_connecting) {
            failed_connecting = true;
        }
    }

    void onMessage(JSONObject json) {
        try {
            handleIncomingMessage(json);
        } catch (PadException e) {
            // TODO real error handling
            e.printStackTrace();
        }
    }

    public synchronized void disconnect() {
        // TODO: sent changes must be considered lost, merge back into pending
        // Though we might want to keep sent changes to check the resync diffs for whether
        // the server did eventually get our last transmission
        if (connection != null) {
            connection.disconnect();
        }
    }

    public synchronized boolean isConnected() {
        return (connection != null && connection.isConnected() && client_vars != null);
    }

    public synchronized boolean isConnecting() {
        return (connection != null && connection.isConnecting()) || (connection != null && connection.isConnected() && client_vars == null);
    }

    public synchronized boolean isAwaitingAck() {
        return (sent_changes != null && !sent_changes.isIdentity());
    }

    public synchronized boolean isSendPending() {
        return (pending_changes != null && !pending_changes.isIdentity());
    }

    public boolean isReadOnly() {
        return read_only;
    }

    public String getReadOnlyId() {
        return read_only_pad_id;
    }

    private void handleIncomingMessage(JSONObject json) throws PadException {
        String type;

        if (json.has("disconnect")) {
            String cause = "UNKNOWN";
            try {
                cause = json.getString("disconnect");
            } catch (JSONException e) {}

            throw new PadException("got disconnect request with cause " + cause);
        }
        
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


    private synchronized void sendClientReady() throws PadException {
        if (connection == null) {
            throw new PadException("no connection to send on");
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

        connection.send(client_ready_json);
    }

    private synchronized void setClientVars(JSONObject json) throws PadException {
        try {
            client_vars = json.getJSONObject("data");
            client_vars_new = true;
            server_time_offset = client_vars.getLong("serverTimestamp") - System.currentTimeMillis();
            user_id = client_vars.getString("userId");

            read_only = client_vars.getBoolean("readonly");
            read_only_pad_id = client_vars.getString("readOnlyId");
            //global_pad_id = client_vars.getString("globalPadId");

            JSONObject collab_client_vars = client_vars.getJSONObject("collab_client_vars");

            long old_server_rev = server_rev;
            String old_server_text = server_text;

            server_text = collab_client_vars.getJSONObject("initialAttributedText").getString("text");
            server_rev = collab_client_vars.getLong("rev");

            if (pending_changes != null && !pending_changes.isIdentity()) {
                // TODO: handle merging in offline changes
                // client should be able to get an update from the server of what has changed
                // since the previous server_rev

                // for now we have to abort if we have local changes that
                // we can't relate to the current server_rev
                if (server_rev != old_server_rev) {
                    throw new PadException("out of date: " + server_rev + " != " + old_server_rev);
                }
            }

            client_text = server_text;
            client_rev = server_rev;

            if (client_vars.has("chatHistory")) {
                JSONArray chat_history = client_vars.getJSONArray("chatHistory");
                for (int i = 0; i < chat_history.length(); i++) {
                    JSONObject chat_entry = chat_history.getJSONObject(i);
                    handleChat(chat_entry.getString("text"), chat_entry.getString("userId"), chat_entry.optString("userName", null), chat_entry.getLong("time"));
                }
            }

            pending_changes = sent_changes = Changeset.identity(server_text.length());
        } catch (JSONException e) {
            throw new PadException("exception getting CLIENT_VARS data", e);
        }
    }

    private synchronized void queueCollabRoom(JSONObject json) throws PadException {
        collabroom_messages.add(json);
    }

    // returns true if there is something new for the client
    public synchronized boolean update(boolean is_sending, boolean is_receiving) throws PadException {
        boolean has_new = false;

        if (is_receiving) {
            if (client_vars != null && client_vars_new) {
                has_new = true;
                client_vars_new = false;
            }

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

            if (connection == null) {
                try {
                    connect();
                } catch (IOException e) {
                    throw new PadException("failed on reconnect attempt", e);
                }
            }
        }

        if (is_sending && !read_only) {
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
        Avatar[] avatars = new Avatar[user_avatars.size()];
        
        client_markers = markers.toArray(client_markers);
        return new TextState(server_text, server_rev, client_text, client_rev, client_markers);
    }

    // only call when synchronized
    // return true if anything was actually sent
    private boolean commitChanges() throws PadException {
        if (connection == null || !connection.isConnected()) {
            return false;
        }

        if ((sent_changes == null || sent_changes.isIdentity()) &&
         (pending_changes != null && !pending_changes.isIdentity())) {
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

            connection.send(user_changes);

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
            String author;

            // DEBUG
            Changeset old_sent_changes = sent_changes;
            Changeset old_pending_changes = pending_changes;

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
                    author = data.getString("author");
                } catch (JSONException e) {
                    throw new PadException("error updating from NEW_CHANGES", e);
                }

                // A' = AB
                Changeset B = new Changeset(cs_str);
                new_text = B.applyToText(server_text);

                // X' = f(B, X)
                // var c2 = c
                Changeset fXB = B;
                Changeset X_prime;

                // if (submittedChangeset) 
                if (!sent_changes.isIdentity()) {
                    // var oldSubmittedChangeset = submittedChangeset;
                    // submittedChangeset = Changeset.follow(c, oldSubmittedChangeset, false, apool);
                    X_prime = Changeset.follow(B, sent_changes, false);
                    // c2 = Changeset.follow(oldSubmittedChangeset, c, true, apool);
                    fXB = Changeset.follow(sent_changes, B, true);
                } else {
                    // this identity just needs to change to reflect the new length
                    X_prime = Changeset.identity(B.newLen);
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

                    // make sure there's a cursor for the editing user
                    if (user_avatars.get(author) == null) {
                        Avatar av = new Avatar(user_id);
                        user_avatars.put(author, av);
                        av.setPos(0,0,0);
                    }
                    for (Iterator<Avatar> i = user_avatars.values().iterator(); i.hasNext(); ) {
                        Avatar a = i.next();
                        a.adjustForChangeset(author, D, new_time - server_time_offset);
                    }

                    has_new = true;
                }
            } catch (ChangesetException e) {
                throw new PadException("NEW_CHANGES broke on "+data, e);
            }

            try {
                // DEBUG: check out that all these follows seem to work as intended
                String server_would_see = pending_changes.applyToText(sent_changes.applyToText(server_text));
                if (!server_would_see.equals(client_text)) {
                    throw new PadException("out of sync, server would see\n'" + server_would_see + "'\nclient sees\n'" + client_text + "'\n");
                }
            } catch (ChangesetException e) {
                System.out.println("old sent changes = " + old_sent_changes.explain());
                System.out.println("old pending changes = " + old_pending_changes.explain());
                try {
                System.out.println("new changeset B=" + new Changeset(cs_str).explain());
                } catch (ChangesetException e2) {}
                System.out.println("sent changes = " + sent_changes.explain());
                System.out.println("pending changes = " + pending_changes.explain());
                System.out.println();
                throw new PadException("broke when checking pendings on new CS "+data, e);
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
            // a user joins or updates status

            JSONObject user_info;
            String user_id;

            try {
                user_info = data.getJSONObject("userInfo");
                user_id = user_info.getString("userId");
            } catch (JSONException e) {
                throw new PadException("bad USER_NEWINFO", e);
            }

            Avatar avatar = user_avatars.get(user_id);

            if (avatar == null) {
                avatar = new Avatar(user_id);
                user_avatars.put(user_id, avatar);
            }

            try {
                String user_name;
                String color_id;
                String user_agent;
                String address;

                if (user_info.isNull("name")) {
                    avatar.setUserName(null);
                } else {
                    avatar.setUserName(user_info.getString("name"));
                }

                int color_num = user_info.optInt("colorId", -1);
                if (color_num != -1) {
                    color_id = client_vars.getJSONArray("colorPalette").optString(color_num);
                } else {
                    color_id = user_info.optString("colorId");
                }
                avatar.setColor(color_id);

                // don't consider cursor stuff "new"
                //has_new = true;

            } catch (JSONException e) {
                throw new PadException("bad/missing data in USER_NEWINFO", e);
            }

        } else if ("USER_LEAVE".equals(collab_type)) {
            // a user leaves
            JSONObject user_info;
            String user_id;

            try {
                user_info = data.getJSONObject("userInfo");
                user_id = user_info.getString("userId");
            } catch (JSONException e) {
                throw new PadException("bad/missing userId in USER_LEAVE", e);
            }

            if (user_avatars.remove(user_id) != null) {
                // don't consider cursor stuff "new"
                //has_new = true;
            }

        } else if ("CHAT_MESSAGE".equals(collab_type)) {
            // message from a user
            try {
                if (handleChat(data.getString("text"), data.getString("userId"), data.optString("userName", null), data.getLong("time"))) {
                    // don't consider cursor stuff "new"
                    //has_new = true;
                }
            } catch (JSONException e) {
                throw new PadException("bad/missing data in CHAT_MESSAGE", e);
            }

        } else {
            System.out.println("unsupported COLLABROOM message type = " + collab_type);
        }

        return has_new;
    }

    // only call when synchronized
    // return true if we've processed this message
    private boolean handleChat(String text, String user_id, String user_name, long time) {
        Matcher m = cursor_chat_regex.matcher(text);
        if (m.find()) {
            String start_pos_str = m.group(1);
            String end_pos_str = m.group(3);

            int start_pos = Integer.parseInt(start_pos_str);
            int end_pos = start_pos-1;

            System.out.println("" + start_pos_str + "," + end_pos_str);

            if (end_pos_str != null) {
                end_pos = Integer.parseInt(end_pos_str);
            }

            Avatar av = user_avatars.get(user_id);

            if (av == null) {
                av = new Avatar(user_id);
                user_avatars.put(user_id, av);
            }

            if (user_name != null) {
                av.setUserName(user_name);
            }

            if (start_pos > client_text.length()) {
                start_pos = client_text.length();
            }
            if (end_pos > client_text.length()) {
                end_pos = client_text.length();
            }
            av.setPos(start_pos, end_pos, time - server_time_offset);

            return true;

        } else {
            System.out.println("ignoring chat message \""+text+"\" from "+user_id);

            return false;
        }
    }

    // ********** Marker manipulation
    // 
    public int registerMarker(int pos, boolean before, boolean valid) {
        synchronized(markers) {
            markers.add(new Marker(pos, before, valid));
            return markers.size()-1;
        }
    }

    public void reRegisterMarker(int idx, int pos, boolean before, boolean valid) {
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

    public void prependText(String new_s) throws PadException {
        try {
            makeChangeInternal(Changeset.simpleEdit(client_text, 0, 0, new_s));
        } catch (ChangesetException e) {
            throw new PadException("error assembling or applying prepend changeset", e);
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

    public Avatar[] getCursors() {
        synchronized (user_avatars) {
            Avatar[] avatars = new Avatar[user_avatars.size()];
            return user_avatars.values().toArray(avatars);
        }
    }

    public synchronized void broadcastCursor(int start_pos, int end_pos) throws PadException {
        if (connection == null) {
            return;
        }

        JSONObject chat_message;
        final StringBuilder text_sb = new StringBuilder("!cursor!");
        text_sb.append(start_pos);
        if (start_pos != end_pos ) {
            text_sb.append('-');
            text_sb.append(end_pos);
        }

        try {
            chat_message = new JSONObject() {{
                put("component", "pad");
                put("type", "COLLABROOM");
                put("data", new JSONObject() {{
                    put("type", "CHAT_MESSAGE");
                    put("text", text_sb.toString());
                }});
            }};
        } catch (JSONException e) {
            throw new PadException("failed building CHAT_MESSAGE JSON", e);
        }

        connection.send(chat_message);
    }

    // ********* private changeset application

    // only call when synchronized!
    private void makeChangeInternal(Changeset changeset) throws ChangesetException {
        pending_changes = Changeset.compose(pending_changes, changeset);

        client_text = changeset.applyToText(client_text);
        client_rev = -1;
        translateMarkers(changeset);

        for (Iterator<Avatar> i = user_avatars.values().iterator(); i.hasNext(); ) {
            Avatar a = i.next();
            // cheating the time here to force it through
            a.adjustForChangeset(user_id, changeset, a.getTime());
        }
    }
}
