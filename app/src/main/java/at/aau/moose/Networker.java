package at.aau.moose;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Calendar;
import java.util.Objects;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class Networker {

    private final String TAG = "Moose_Networker";

    private static Networker self; // For singleton

    private Socket socket;
    private PrintStream outChannel;
    private BufferedReader inChannel;

    private Observable<Object> receiverOberservable;

    /**
     * Get the singletong instance
     * @return self instance
     */
    public static Networker get() {
        if (self == null) self = new Networker();
        return self;
    }

    /**
     * Constructor
     */
    private Networker() {
        // Create receiverObserable (starts after connection is established)
        receiverOberservable = Observable.fromAction(() -> {
            Log.d(TAG, "Receiving data...");
            // Continously read lines from server until DISCONNECT is received
            try {
                String line;
                while (true) {
                    line = inChannel.readLine();
                    if (line != null) {
                        Log.d(TAG, "Received: " + line);
                        processInput(line);
                    } else break;
                }
                connect();
            } catch (Exception e) {
                // Try to reconnect
                connect();
            }

//            System.exit(0);
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Subscribe to get the messages (to send to the desktop)
     * @param mssgPublisher Publisher of String messages
     */
    public void subscribeToMessages(PublishSubject<String> mssgPublisher) {
        mssgPublisher
                .observeOn(Schedulers.io())
                .subscribe(actionStr -> {
                    sendAction(actionStr);
                });
    }

    /**
     * Connect to Empenvi
     */
    public void connect() {
        // Connect to server (runs once)
        new ConnectTask().execute();
    }

    /**
     * Process the input command
     * @param inStr Input String
     */
    private void processInput(String inStr) {
        Log.d(TAG, "Process: " + inStr);
        // Command must be in the format mssg_param
        // Message and param
        String mssg = Utils.splitStr(inStr)[0];
        String param = Utils.splitStr(inStr)[1];

        // Command
        switch (mssg) {
        case Strs.MSSG_TECHNIQUE:
            Actioner.get().setTechnique(param);
            break;
        case Strs.MSSG_PID:
            // Participant's ID
//            Mologger.get().setupParticipantLog(param);
            Mologger.get().logParticipant(param);
            break;
        case Strs.MSSG_BEG_PHS:
            // Log the start of the phase
//            Mologger.get().logPhaseStart(param);
            Mologger.get()._phase = param;
            break;
        case Strs.MSSG_SBLK:
            int sblkNum = Integer.parseInt(param);
            Mologger.get()._subblockNum = sblkNum;
            break;
        case Strs.MSSG_TRL:
            int trlNum = Integer.parseInt(param);
            Mologger.get()._trialNum = trlNum;
            break;
        case Strs.MSSG_BEG_EXP:
            // Tell the MainActivity to begin experimente
            MainActivity.beginExperiment();

            // Experiment description
            Mologger.get().setupExperimentLog(param);
            Mologger.get().setLogState(true);
            break;

        case Strs.MSSG_BEG_BLK:
            // Get the experiment number
            int blkNum = Integer.parseInt(param);
//            Mologger.get().setupBlockLog(blkNum);
//            Mologger.get().logBlockStart(blkNum);

            break;

        case Strs.MSSG_END_TRL:
            Mologger.get().finishTrialLog();
            break;

        case Strs.MSSG_END_BLK:
            Mologger.get().finishBlockLog();
            break;

        case Strs.MSSG_END_EXP:
            Mologger.get().setLogState(false);
            break;

        case Strs.MSSG_BEG_LOG:
            Actioner.get().isTrialRunning = true;
            break;

        case Strs.MSSG_END_LOG:
            Actioner.get().isTrialRunning = false;
            break;

        case Strs.NET_DISCONNECT:
            connect();
            break;
        }
    }

    /**
     * Send an action string to the Expenvi
     * @param actStr String action (from Constants)
     */
    public void sendAction(String actStr) {
        if (outChannel != null) {
            outChannel.println(actStr);
            outChannel.flush();
            Log.d(TAG, actStr + " sent to server");
        } else {
            Log.d(TAG, "Out channel not available!");
        }
    }

    /**
     * Connections task (Background)
     */
    @SuppressLint("StaticFieldLeak")
    private class ConnectTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... tasks) {

            Log.d(TAG, "Connecting to Expenvi...");

            while (true) {
                try {
                    // Open the socket
                    socket = new Socket(Config.SERVER_IP, Config.SERVER_Port);
                    Log.d(TAG, "Socket opened");
                    // Create streams for I/O
                    outChannel = new PrintStream(socket.getOutputStream());
                    inChannel = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    Log.d(TAG, "Channels opened");

                    // Send the first message and get the reply for connection confirmation
                    outChannel.println(Strs.MSSG_MOOSE);
                    String line = inChannel.readLine();
                    Log.d(TAG, "Line: " + line);
                    if (Objects.equals(line, Strs.MSSG_CONFIRM)) { // Confirmation
                        Log.d(TAG, "Connection Successful!");
                        return "SUCCESS";
                    }

                    return "FAIL";

                } catch (IOException e) {
//                    e.printStackTrace();
                    Log.d(TAG, e.toString());
                    Log.d(TAG, "Reconnecting...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }

//            return "FAIL";
        }

        @Override
        protected void onPostExecute(String s) {
            if (Objects.equals(s, "SUCCESS")) {
                Log.d(TAG, "Start receiving data...");
                // Start receiving data from server
                receiverOberservable.subscribe();
            } else {
                Log.d(TAG, "Connection failed!");
                // Try to reconnect
                connect();
            }
        }
    }

    /**
     * Return the time in ms
     * @return Time (ms)
     */
    private long now() {
        return Calendar.getInstance().getTimeInMillis();
    }

}



