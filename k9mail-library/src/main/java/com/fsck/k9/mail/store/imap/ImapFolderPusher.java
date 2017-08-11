package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.os.PowerManager;

import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.PushReceiver;
import com.fsck.k9.mail.power.TracingPowerManager;
import com.fsck.k9.mail.power.TracingPowerManager.TracingWakeLock;
import com.fsck.k9.mail.store.RemoteStore;
import timber.log.Timber;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RO;
import static com.fsck.k9.mail.K9MailLib.PUSH_WAKE_LOCK_TIMEOUT;
import static com.fsck.k9.mail.store.imap.ImapResponseParser.equalsIgnoreCase;


class ImapFolderPusher {
    private static final int IDLE_READ_TIMEOUT_INCREMENT = 5 * 60 * 1000;
    private static final int IDLE_FAILURE_COUNT_LIMIT = 10;
    private static final int MAX_DELAY_TIME = 5 * 60 * 1000; // 5 minutes
    private static final int NORMAL_DELAY_TIME = 5000;

    private final ImapFolder folder;
    private final PushReceiver pushReceiver;
    private final Object threadLock = new Object();
    private final IdleStopper idleStopper = new IdleStopper();
    private final TracingWakeLock wakeLock;
    private final List<ImapResponse> storedUntaggedResponses = new ArrayList<>();
    private Thread listeningThread;
    private volatile boolean stop = false;
    private volatile boolean idling = false;

    ImapFolderPusher(ImapStore store, String folderName, PushReceiver pushReceiver) {
        this.pushReceiver = pushReceiver;

        folder = new ImapFolder(store, folderName);
        Context context = pushReceiver.getContext();
        TracingPowerManager powerManager = TracingPowerManager.getPowerManager(context);
        String tag = "ImapFolderPusher " + store.getStoreConfig().toString() + ":" + folderName;
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.setReferenceCounted(false);
    }

    public void start() {
        synchronized (threadLock) {
            if (listeningThread != null) {
                throw new IllegalStateException("start() called twice");
            }

            listeningThread = new Thread(new PushRunnable());
            listeningThread.start();
        }
    }

    public void refresh() throws IOException, MessagingException {
        if (idling) {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);
            idleStopper.stopIdle();
        }
    }

    public void stop() {
        synchronized (threadLock) {
            if (listeningThread == null) {
                throw new IllegalStateException("stop() called twice");
            }

            stop = true;

            listeningThread.interrupt();
            listeningThread = null;
        }

        if (folder != null && folder.isOpen()) {
            if (K9MailLib.isDebug()) {
                Timber.v("Closing folder to stop pushing for %s", getLogId());
            }

            folder.close();
        } else {
            Timber.w("Attempt to interrupt null connection to stop pushing on folderPusher for %s", getLogId());
        }
    }

    private boolean isUntaggedResponseSupported(ImapResponse response) {
        return (equalsIgnoreCase(response.get(1), "EXISTS") || equalsIgnoreCase(response.get(1), "EXPUNGE") ||
                equalsIgnoreCase(response.get(1), "FETCH") || equalsIgnoreCase(response.get(0), "VANISHED"));
    }

    private String getLogId() {
        return folder.getLogId();
    }

    String getName() {
        return folder.getName();
    }

    private class PushRunnable implements Runnable, UntaggedHandler {
        private int delayTime = NORMAL_DELAY_TIME;
        private int idleFailureCount = 0;
        private boolean needsPoll = false;

        @Override
        public void run() {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

            if (K9MailLib.isDebug()) {
                Timber.i("Pusher starting for %s", getLogId());
            }

            long lastUidNext = -1L;
            while (!stop) {
                try {
                    long oldUidNext = getOldUidNext();

                        /*
                         * This makes sure 'oldUidNext' is never smaller than 'UIDNEXT' from
                         * the last loop iteration. This way we avoid looping endlessly causing
                         * the battery to drain.
                         *
                         * See issue 4907
                         */
                    if (oldUidNext < lastUidNext) {
                        oldUidNext = lastUidNext;
                    }

                    boolean openedNewConnection = openConnectionIfNecessary();

                    if (stop) {
                        break;
                    }

                    boolean pushPollOnConnect = folder.getStore().getStoreConfig().isPushPollOnConnect();
                    if (pushPollOnConnect && (openedNewConnection || needsPoll)) {
                        needsPoll = false;
                        pushReceiver.syncFolder(getName());
                    }

                    if (stop) {
                        break;
                    }

                    long newUidNext = getNewUidNext();
                    lastUidNext = newUidNext;
                    long startUid = getStartUid(oldUidNext, newUidNext);

                    if (newUidNext > startUid) {
                        pushReceiver.syncFolder(getName());
                    } else {
                        if (K9MailLib.isDebug()) {
                            Timber.i("About to IDLE for %s", getLogId());
                        }

                        prepareForIdle();

                        ImapConnection conn = folder.getConnection();
                        setReadTimeoutForIdle(conn);
                        sendIdle(conn);

                        returnFromIdle();
                    }
                } catch (AuthenticationFailedException e) {
                    reacquireWakeLockAndCleanUp();

                    if (K9MailLib.isDebug()) {
                        Timber.e(e, "Authentication failed. Stopping ImapFolderPusher.");
                    }

                    pushReceiver.authenticationFailed();
                    stop = true;
                } catch (Exception e) {
                    reacquireWakeLockAndCleanUp();

                    if (stop) {
                        Timber.i("Got exception while idling, but stop is set for %s", getLogId());
                    } else {
                        pushReceiver.pushError("Push error for " + getName(), e);
                        Timber.e("Got exception while idling for %s", getLogId());

                        pushReceiver.sleep(wakeLock, delayTime);

                        delayTime *= 2;
                        if (delayTime > MAX_DELAY_TIME) {
                            delayTime = MAX_DELAY_TIME;
                        }

                        idleFailureCount++;
                        if (idleFailureCount > IDLE_FAILURE_COUNT_LIMIT) {
                            Timber.e("Disabling pusher for %s after %d consecutive errors", getLogId(), idleFailureCount);
                            pushReceiver.pushError("Push disabled for " + getName() + " after " + idleFailureCount +
                                    " consecutive errors", e);
                            stop = true;
                        }
                    }
                }
            }

            pushReceiver.setPushActive(getName(), false);

            try {
                if (K9MailLib.isDebug()) {
                    Timber.i("Pusher for %s is exiting", getLogId());
                }

                folder.close();
            } catch (Exception me) {
                Timber.e(me, "Got exception while closing for %s", getLogId());
            } finally {
                wakeLock.release();
            }
        }

        private void reacquireWakeLockAndCleanUp() {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

            clearStoredUntaggedResponses();
            idling = false;
            pushReceiver.setPushActive(getName(), false);

            try {
                folder.close();
            } catch (Exception me) {
                Timber.e(me, "Got exception while closing for exception for %s", getLogId());
            }
        }

        private long getNewUidNext() throws MessagingException {
            long newUidNext = folder.getUidNext();
            if (newUidNext != -1L) {
                return newUidNext;
            }

            if (K9MailLib.isDebug()) {
                Timber.d("uidNext is -1, using search to find highest UID");
            }

            long highestUid = folder.getHighestUid();
            if (highestUid == -1L) {
                return -1L;
            }

            newUidNext = highestUid + 1;

            if (K9MailLib.isDebug()) {
                Timber.d("highest UID = %d, set newUidNext to %d", highestUid, newUidNext);
            }

            return newUidNext;
        }

        private long getStartUid(long oldUidNext, long newUidNext) {
            long startUid = oldUidNext;
            int displayCount = folder.getStore().getStoreConfig().getDisplayCount();

            if (startUid < newUidNext - displayCount) {
                startUid = newUidNext - displayCount;
            }

            if (startUid < 1) {
                startUid = 1;
            }

            return startUid;
        }

        private void prepareForIdle() {
            pushReceiver.setPushActive(getName(), true);
            idling = true;
        }

        private void sendIdle(ImapConnection conn) throws MessagingException, IOException {
            try {
                try {
                    folder.executeSimpleCommand(Commands.IDLE, this);
                } finally {
                    idleStopper.stopAcceptingDoneContinuation();
                }
            } catch (IOException e) {
                conn.close();
                throw e;
            }
        }

        private void returnFromIdle() {
            idling = false;
            delayTime = NORMAL_DELAY_TIME;
            idleFailureCount = 0;
        }

        private boolean openConnectionIfNecessary() throws MessagingException {
            ImapConnection oldConnection = folder.getConnection();
            folder.open(OPEN_MODE_RO);

            ImapConnection conn = folder.getConnection();

            checkConnectionNotNull(conn);
            checkConnectionIdleCapable(conn);

            return conn != oldConnection;
        }

        private void checkConnectionNotNull(ImapConnection conn) throws MessagingException {
            if (conn == null) {
                String message = "Could not establish connection for IDLE";
                pushReceiver.pushError(message, null);

                throw new MessagingException(message);
            }
        }

        private void checkConnectionIdleCapable(ImapConnection conn) throws MessagingException {
            if (!conn.isIdleCapable()) {
                stop = true;

                String message = "IMAP server is not IDLE capable: " + conn.toString();
                pushReceiver.pushError(message, null);

                throw new MessagingException(message);
            }
        }

        private void setReadTimeoutForIdle(ImapConnection conn) throws SocketException {
            int idleRefreshTimeout = folder.getStore().getStoreConfig().getIdleRefreshMinutes() * 60 * 1000;
            conn.setReadTimeout(idleRefreshTimeout + IDLE_READ_TIMEOUT_INCREMENT);
        }

        @Override
        public void handleAsyncUntaggedResponse(ImapResponse response) {
            if (K9MailLib.isDebug()) {
                Timber.v("Got async response: %s", response);
            }

            if (stop) {
                if (K9MailLib.isDebug()) {
                    Timber.d("Got async untagged response: %s, but stop is set for %s", response, getLogId());
                }

                idleStopper.stopIdle();
            } else {
                if (response.getTag() == null) {
                    if (response.size() > 1) {
                        if (isUntaggedResponseSupported(response)) {
                            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

                            if (K9MailLib.isDebug()) {
                                Timber.d("Got useful async untagged response: %s for %s", response, getLogId());
                            }

                            synchronized (storedUntaggedResponses) {
                                storedUntaggedResponses.add(response);
                            }

                            processStoredUntaggedResponses();
                        }
                    } else if (response.isContinuationRequested()) {
                        if (K9MailLib.isDebug()) {
                            Timber.d("Idling %s", getLogId());
                        }

                        idleStopper.startAcceptingDoneContinuation(folder.getConnection());
                        wakeLock.release();
                    }
                }
            }
        }

        private void clearStoredUntaggedResponses() {
            synchronized (storedUntaggedResponses) {
                storedUntaggedResponses.clear();
            }
        }

        private void processStoredUntaggedResponses() {
            while (true) {
                List<ImapResponse> untaggedResponses = getAndClearStoredUntaggedResponses();
                if (untaggedResponses.isEmpty()) {
                    break;
                }

                if (K9MailLib.isDebug()) {
                    Timber.i("Processing %d untagged responses from previous commands for %s",
                            untaggedResponses.size(), getLogId());
                }

                pushReceiver.syncFolder(getName());
            }
        }

        private List<ImapResponse> getAndClearStoredUntaggedResponses() {
            synchronized (storedUntaggedResponses) {
                if (storedUntaggedResponses.isEmpty()) {
                    return Collections.emptyList();
                }

                List<ImapResponse> untaggedResponses = new ArrayList<>(storedUntaggedResponses);
                storedUntaggedResponses.clear();

                return untaggedResponses;
            }
        }

        private long getOldUidNext() {
            long oldUidNext = -1L;
            try {
                String serializedPushState = pushReceiver.getPushState(getName());
                ImapPushState pushState = ImapPushState.parse(serializedPushState);
                oldUidNext = pushState.uidNext;

                if (K9MailLib.isDebug()) {
                    Timber.i("Got oldUidNext %d for %s", oldUidNext, getLogId());
                }
            } catch (Exception e) {
                Timber.e(e, "Unable to get oldUidNext for %s", getLogId());
            }

            return oldUidNext;
        }
    }

    /**
     * Ensure the DONE continuation is only sent when the IDLE command was sent and hasn't completed yet.
     */
    private static class IdleStopper {
        private boolean acceptDoneContinuation = false;
        private ImapConnection imapConnection;


        synchronized void startAcceptingDoneContinuation(ImapConnection connection) {
            if (connection == null) {
                throw new NullPointerException("connection must not be null");
            }

            acceptDoneContinuation = true;
            imapConnection = connection;
        }

        synchronized void stopAcceptingDoneContinuation() {
            acceptDoneContinuation = false;
            imapConnection = null;
        }

        synchronized void stopIdle() {
            if (acceptDoneContinuation) {
                acceptDoneContinuation = false;
                sendDone();
            }
        }

        private void sendDone() {
            try {
                imapConnection.setReadTimeout(RemoteStore.SOCKET_READ_TIMEOUT);
                imapConnection.sendContinuation("DONE");
            } catch (IOException e) {
                imapConnection.close();
            }
        }
    }
}
