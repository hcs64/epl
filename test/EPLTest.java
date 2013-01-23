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
            String name = Thread.currentThread().getName();
            Random r = new Random();
            int send_delay = 0;
            int recv_delay = 0;

            try {
                connect();

                while (!p.isConnected()) {
                    try {
                        Thread.sleep(wait_loop_delay);
                    } catch (InterruptedException e) {}
                }

                for (int i = 0; i < iterations; i++) {
                    boolean new_updates;

                    boolean is_sending, is_receiving;

                    is_sending = (send_delay == 0);
                    is_receiving = (recv_delay == 0);
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
                        if (r.nextBoolean()) {
                            p.appendText("e"+name+String.valueOf(Math.random()));
                            p.appendText("\n");
                        } else {
                            p.prependText("p"+name+String.valueOf(Math.random()+"\n"));
                        }
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
        final int test_count = 4;

        Test tests[] = new Test[test_count];
        Thread threads[] = new Thread[test_count];

        for (int i = 0; i < test_count; i++) {
            Test t = tests[i] = new Test();
            threads[i] = new Thread(t, "test"+i);

            try {
                t.pad_url = new URL("http://10.0.2.15:9001/");
            } catch (MalformedURLException e) {}
            t.max_send_delay = 5;
            t.max_recv_delay = 5;
            t.wait_loop_delay = 30;
            t.iterations = 1000;


        }

        for (int i = 0; i < test_count; i++) {
            threads[i].start();
        }

        for (int i = 0; i < test_count; i++) {
            Thread thread = threads[i];
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {}
            }
        }

        // flush anything we may have delayed
        for (int i = 0; i < test_count; i++) {
            Test t = tests[i];
            try {
                t.p.update(true, true);
            } catch (PadException e) {
                e.printStackTrace();
            }
        }

        // give everyone one last chance to sync up
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {}


        for (int i = 0; i < test_count; i++) {
            Test t = tests[i];
            try {
                t.p.update(true, true);
            } catch (PadException e) {
                e.printStackTrace();
            }
        }

        TextState ts0 = tests[0].p.getState();

        boolean tests_ok = true;

        for (int i = 1; i < test_count; i++) {
            TextState ts = tests[i].p.getState();
            if (ts0.client_text.equals(ts.client_text)) {
                // OK
            } else {
                tests_ok = false;
            }
            
            tests[i].p.disconnect();
        }

        System.out.println("********");
        System.out.println(ts0.client_text.substring(0, Math.min(100, ts0.client_text.length())));
        if (tests_ok) {
            System.out.println("client_texts are equal");
        } else {
            System.out.println("ERR: not equal");
        }
        System.out.println("********");

    }
}
