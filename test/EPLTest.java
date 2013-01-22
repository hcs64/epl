import epl.*;

import java.net.URL;
import java.net.MalformedURLException;
import java.io.IOException;
import java.util.Random;

public class EPLTest {
    static class Test implements Runnable {
        URL pad_url;
        int max_send_delay;
        int max_recv_delay;
        int wait_loop_delay;
        int iterations;

        Pad p;

        private void connect() throws IOException, PadException {
            p = new Pad(
                pad_url,
                "",     // client_id
                null,   // token
                "stresspad",
                null);  // session_token

            p.connect();
        }

        private void disconnect() {
            p.disconnect();
        }

        public void run() {
            Random r = new Random();
            int send_delay = 0;
            int recv_delay = 0;

            try {
                connect();

                for (int i = 0; i < iterations; i++) {
                    boolean new_updates;

                    boolean is_sending, is_receiving;

                    is_sending = (send_delay == 0);
                    is_receiving= (recv_delay == 0);
                    new_updates = p.update(is_sending, is_receiving);

                    if (send_delay == 0) {
                        send_delay = r.nextInt(max_send_delay+1);   // +1 as n is noninclusive
                    } else {
                        send_delay --;
                    }

                    if (recv_delay == 0) {
                        recv_delay = r.nextInt(max_recv_delay+1);
                    } else {
                        recv_delay --;
                    }

                    if (new_updates) {
                        TextState ts = p.getState();
                        System.out.println("Server rev: " + ts.server_rev);
                        System.out.println("Client rev: " + ts.client_rev);
                        //System.out.println("Client text: " + ts.client_text);
                    }

                    if (p.isConnected()) {
                        p.appendText(String.valueOf(Math.random()));
                        p.appendText("\n");
                    }

                    try {
                        Thread.sleep(wait_loop_delay);
                    } catch (InterruptedException e) {}
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (PadException e) {
                e.printStackTrace();
            } finally {
                /*
                try {
                    p.update(true, true);
                } catch (PadException e) {}
                disconnect();
                */
            }
        }
    }

    public static void main(String args[]) {
        System.out.println("Hello world");

        Test test1 = new Test();
        Thread t1 = new Thread(test1, "test1");

        try {
            test1.pad_url = new URL("http://10.0.2.15:9001/");
        } catch (MalformedURLException e) {}
        test1.max_send_delay = 0;
        test1.max_recv_delay = 0;
        test1.wait_loop_delay = 30;
        test1.iterations = 1000;

        t1.start();

        Test test2 = new Test();
        Thread t2 = new Thread(test2, "test2");

        try {
            test2.pad_url = new URL("http://10.0.2.15:9001/");
        } catch (MalformedURLException e) {}
        test2.max_send_delay = 10;
        test2.max_recv_delay = 10;
        test2.wait_loop_delay = 30;
        test2.iterations = 1000;

        t2.start();

        while (t2.isAlive()) {
            try {
                t2.join();
            } catch (InterruptedException e) {}
        }

        while (t1.isAlive()) {
            try {
                t1.join();
            } catch (InterruptedException e) {}
        }

        try {
            test1.p.update(true, true);
            test2.p.update(true, true);
        } catch (PadException e) {
            e.printStackTrace();
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {}

        try {
            test1.p.update(true, true);
            test2.p.update(true, true);
        } catch (PadException e) {
            e.printStackTrace();
        }


        TextState ts1 = test1.p.getState();
        TextState ts2 = test2.p.getState();

        test1.p.disconnect();
        test2.p.disconnect();

        System.out.println("********");
        System.out.println(ts1.client_text.substring(0, Math.min(100, ts1.client_text.length())));
        if (ts1.client_text.equals(ts2.client_text)) {
            System.out.println("client_texts are equal");
        } else {
            System.out.println("ERR: not equal");
        }
        System.out.println("********");

    }
}
