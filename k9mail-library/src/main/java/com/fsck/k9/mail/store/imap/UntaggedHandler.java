package com.fsck.k9.mail.store.imap;

import java.io.IOException;

import com.fsck.k9.mail.MessagingException;


interface UntaggedHandler {
    void handleAsyncUntaggedResponse(ImapResponse response) throws IOException, MessagingException;
}
