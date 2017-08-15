package com.fsck.k9.mail;

import java.util.List;

import android.content.Context;

import com.fsck.k9.mail.power.TracingPowerManager.TracingWakeLock;

public interface PushReceiver {
    Context getContext();
    void syncFolder(String folderName);
    void messageFlagsChanged(String folderName, Message message);
    void messagesRemoved(String folderName, List<String> messageUids);
    void highestModSeqChanged(String folderName, long highestModSeq);
    String getPushState(String folderName);
    void pushError(String errorMessage, Exception e);
    void authenticationFailed();
    void setPushActive(String folderName, boolean enabled);
    void sleep(TracingWakeLock wakeLock, long millis);
}
